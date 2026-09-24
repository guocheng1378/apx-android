$h = @{ 'User-Agent' = 'apx-check' }
"== GitHub 最新提交 =="
$r = Invoke-RestMethod 'https://api.github.com/repos/guocheng1378/apx-android/commits?per_page=8' -Headers $h
$r | ForEach-Object {
    "{0}  {1}  {2}" -f $_.sha.Substring(0, 8), $_.commit.committer.date, ($_.commit.message -split "`n")[0]
}
"== Release =="
try {
    $rel = Invoke-RestMethod 'https://api.github.com/repos/guocheng1378/apx-android/releases' -Headers $h
    $rel | ForEach-Object {
        "tag=$($_.tag_name) name=$($_.name) published=$($_.published_at)"
        $_.assets | ForEach-Object { "  asset: $($_.name) ($([math]::Round($_.size/1MB,1)) MB, 下载 $($_.download_count))" }
    }
} catch { "RELEASE_FAIL: $($_.Exception.Message)" }
"== 本地 vs 远端 =="
git -C C:\Users\Administrator\Desktop\全能外设 log --oneline -3
