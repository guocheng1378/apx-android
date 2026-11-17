package com.allperiph.shared.net

import android.content.Context
import android.util.Log
import com.southernstorm.noise.protocol.CipherState
import com.southernstorm.noise.protocol.CipherStatePair
import com.southernstorm.noise.protocol.HandshakeState
import java.security.MessageDigest
import java.util.Base64

/**
 * R2 通道加密 —— Noise 会话封装（docs/REQ-无线安全配对.md §4.1–§4.3）。
 *
 * 协议：NoisePSK_IK_25519_ChaChaPoly_SHA256（与 PC 端 noise-c 参考实现互通，
 * PC 侧镜像在 pc/host/src/wireless/noise_session.cpp —— 参数改动必须两边同步）：
 *   · prologue = "APX1-9511-v1"（定死，防跨协议混淆）；
 *   · PSK = SHA-256(UTF-8(pairingToken)) 恒 32 字节（token 不定长，哈希定长）；
 *   · 模式 IK：双方静态 Curve25519 密钥（信标第 5 段携带公钥，TOFU 升白名单），
 *     前向保密由每连接临时密钥提供；PSK 保证「持有 token 才能完成握手」。
 *
 * 握手帧 0x35 恒明文：Initiator 发 msg1（[writeHandshakeMsg]），Responder 消费 msg1
 * 后回 msg2；[split] 后进入传输态，[encrypt]/[decrypt] 在帧 payload 整体（含 CRC 尾）
 * 上做 AD 为空的 AEAD，帧头（APX1 16 字节 + 长度）保持明文。
 *
 * 一个实例只服务一条连接；跨连接请重建实例。非线程安全 —— 调用方保证
 * 读侧（pump 线程）与写侧各自持锁（见 ControlServer / TvControllerClient 用法）。
 */
class NoiseSession {
    private var hs: HandshakeState? = null
    private var csSend: CipherState? = null
    private var csRecv: CipherState? = null

    /** 发起方：准备 msg1。token=配对令牌（PSK 源）；peerPub=对端静态公钥（32 字节）；
     *  ownPriv=本端静态私钥。成功返回 true。 */
    fun beginInitiator(token: ByteArray, peerPub: ByteArray, ownPriv: ByteArray): Boolean {
        return try {
            val s = HandshakeState(PROTOCOL_NAME, HandshakeState.INITIATOR)
            s.setPrologue(PROLOGUE.toByteArray(Charsets.UTF_8), 0, PROLOGUE.length)
            s.setPreSharedKey(derivePsk(token), 0, 32)
            s.localKeyPair.setPrivateKey(ownPriv, 0)
            s.remotePublicKey.setPublicKey(peerPub, 0)
            s.start()
            if (s.action != HandshakeState.WRITE_MESSAGE) return false
            hs = s
            true
        } catch (t: Throwable) {
            Log.w(TAG, "beginInitiator 失败: ${t.message}")
            false
        }
    }

    /** 应答方：等待 msg1。成功返回 true。 */
    fun beginResponder(token: ByteArray, ownPriv: ByteArray): Boolean {
        return try {
            val s = HandshakeState(PROTOCOL_NAME, HandshakeState.RESPONDER)
            s.setPrologue(PROLOGUE.toByteArray(Charsets.UTF_8), 0, PROLOGUE.length)
            s.setPreSharedKey(derivePsk(token), 0, 32)
            s.localKeyPair.setPrivateKey(ownPriv, 0)
            s.start()
            if (s.action != HandshakeState.READ_MESSAGE) return false
            hs = s
            true
        } catch (t: Throwable) {
            Log.w(TAG, "beginResponder 失败: ${t.message}")
            false
        }
    }

    /** 生成下一条握手消息（msg1/msg2），返回写入 out 的字节数；-1 = 状态机不在可写态。 */
    fun writeHandshakeMsg(out: ByteArray): Int {
        val s = hs ?: return -1
        return try {
            // 握手帧 payload 恒空 —— 握手只交换 Noise 自身的交换段，业务帧不搭车
            //（明/密分界只有一条：0x35 恒明文，之后全密文）。
            s.writeMessage(out, 0, ByteArray(0), 0, 0)
        } catch (t: Throwable) {
            Log.w(TAG, "writeHandshakeMsg 失败: ${t.message}")
            -1
        }
    }

    /** 消费一条收到的握手消息（msg2/msg1）。0 = 成功；-1 = 解析失败（token 不符等）。 */
    fun readHandshakeMsg(data: ByteArray, offset: Int, length: Int): Int {
        val s = hs ?: return -1
        return try {
            s.readMessage(data, offset, length, ByteArray(1), 0)
            0
        } catch (t: Throwable) {
            // MAC 失败 = 对端不是持同一 token 的设备（或握手帧被改过），握手作废。
            Log.w(TAG, "readHandshakeMsg 失败: ${t.message}")
            -1
        }
    }

    /** 握手完成后切出传输态密钥（两端各消费/产出一条消息后调用）。 */
    fun split(): Boolean {
        val s = hs ?: return false
        if (s.action != HandshakeState.SPLIT) return false
        val pair: CipherStatePair = s.split()
        csSend = pair.sender
        csRecv = pair.receiver
        hs = null   // 握手态使命完成（密钥已进传输态），释放
        return true
    }

    /**
     * 传输态：加密 buf[0..payloadLen)（原位），返回密文长度（payloadLen+16）；
     * 0 = 失败（未 split 或明文超 [MAX_PLAIN]）。
     */
    fun encrypt(buf: ByteArray, payloadLen: Int): Int {
        val cs = csSend ?: return 0
        if (payloadLen <= 0 || payloadLen > MAX_PLAIN) return 0
        return try {
            // 明密文可同 buffer 原位（noise-java 支持同 offset in-place）。
            cs.encryptWithAd(null, buf, 0, buf, 0, payloadLen)
            payloadLen + cs.macLength
        } catch (t: Throwable) {
            Log.w(TAG, "encrypt 失败: ${t.message}")
            0
        }
    }

    /**
     * 传输态：解密 buf[0..dataLen)（原位，密文含 16 字节标签），返回明文长度；
     * 0 = 失败（标签校验不过 = 帧被篡改/密钥不对，调用方按丢帧计数处理，不断链）。
     */
    fun decrypt(buf: ByteArray, dataLen: Int): Int {
        val cs = csRecv ?: return 0
        if (dataLen <= cs.macLength) return 0
        return try {
            cs.decryptWithAd(null, buf, 0, buf, 0, dataLen)
            dataLen - cs.macLength
        } catch (t: Throwable) {
            // 标签不过常见于「一端先断、另一端还在发」或被篡改 —— 计数不抛。
            Log.w(TAG, "decrypt 失败: ${t.message}")
            0
        }
    }

    companion object {
        private const val TAG = "NoiseSession"
        const val PROTOCOL_NAME = "NoisePSK_IK_25519_ChaChaPoly_SHA256"
        const val PROLOGUE = "APX1-9511-v1"
        /** 传输态单条消息明文上限（PC 端 noise-c 强校验 NOISE_MAX_PAYLOAD_LEN-16；
         *  Java 侧虽不检查，为两端一致同样按此截断发送）。 */
        const val MAX_PLAIN = 65519
        const val KEY_LEN = 32
        const val PSK_LEN = 32

        /** PSK 派生：SHA-256(UTF-8(token))，恒 32 字节（与 PC 端 derivePsk 同式）。 */
        fun derivePsk(token: ByteArray): ByteArray =
            MessageDigest.getInstance("SHA-256").digest(token)

        /** 生成一对新的 Curve25519 静态密钥（noise-java 自带系统随机源）。 */
        fun generateKeyPair(): Pair<ByteArray, ByteArray> {
            val s = HandshakeState(PROTOCOL_NAME, HandshakeState.INITIATOR)
            s.localKeyPair.generateKeyPair()
            val priv = ByteArray(KEY_LEN)
            val pub = ByteArray(KEY_LEN)
            s.localKeyPair.getPrivateKey(priv, 0)
            s.localKeyPair.getPublicKey(pub, 0)
            return priv to pub
        }

        /** 由静态私钥派生 Curve25519 公钥（不落任何状态）。 */
        fun publicFromPrivate(priv: ByteArray): ByteArray {
            val s = HandshakeState(PROTOCOL_NAME, HandshakeState.INITIATOR)
            s.localKeyPair.setPrivateKey(priv, 0)
            val pub = ByteArray(KEY_LEN)
            s.localKeyPair.getPublicKey(pub, 0)
            return pub
        }

        /**
         * 进程级懒加载本端静态密钥对（与 PC 端 ensureNoiseKeyPair 同款节奏）：
         * prefs "apx_noise" 里 noiseKeyPriv（Base64 32 字节）→ 有则派生公钥；
         * 无则生成并落盘。返回 (priv, pub)；失败返回 null（调用方回退明文链路）。
         */
        @Volatile private var cached: Pair<ByteArray, ByteArray>? = null

        fun ensureKeyPair(context: Context): Pair<ByteArray, ByteArray>? {
            cached?.let { return it }
            // 块体函数须显式 return：取 synchronized 求值结果直接返回。
            return synchronized(this) {
                cached?.let { return it }
                try {
                    val prefs = context.getSharedPreferences("apx_noise", Context.MODE_PRIVATE)
                    val saved = prefs.getString("noiseKeyPriv", null)
                    val pair = if (!saved.isNullOrBlank()) {
                        val priv = Base64.getDecoder().decode(saved)
                        if (priv.size == KEY_LEN) priv to publicFromPrivate(priv) else null
                    } else null
                    val kp = pair ?: generateKeyPair().also {
                        prefs.edit().putString(
                            "noiseKeyPriv",
                            Base64.getEncoder().encodeToString(it.first)
                        ).apply()
                        Log.i(TAG, "首次生成 R2 通道加密静态密钥对并落盘")
                    }
                    cached = kp
                    kp
                } catch (t: Throwable) {
                    Log.w(TAG, "ensureKeyPair 失败: ${t.message}")
                    null
                }
            }
        }
    }
}
