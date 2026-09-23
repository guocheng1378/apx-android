Add-Type @"
using System;using System.Text;using System.Runtime.InteropServices;
public class W8{
 public delegate bool EnumCb(IntPtr h,IntPtr l);
 [DllImport("user32.dll")]public static extern bool EnumWindows(EnumCb cb,IntPtr l);
 [DllImport("user32.dll")]public static extern int GetWindowText(IntPtr h,StringBuilder s,int n);
 [DllImport("user32.dll")]public static extern bool IsWindowVisible(IntPtr h);
 [DllImport("user32.dll")]public static extern int GetClassName(IntPtr h,StringBuilder s,int n);
 [DllImport("user32.dll")]public static extern IntPtr FindWindowW(string cls, string title);
 public static string Report() {
     var sbOut = new System.Collections.Generic.List<string>();
     EnumWindows((h,l) => {
         var sb=new StringBuilder(256);
         GetWindowText(h,sb,256);
         var t=sb.ToString();
         if (t.Length>0 && (t.Contains("全能")||t.Contains("外设"))) {
             var cn=new StringBuilder(256); GetClassName(h,cn,256);
             sbOut.Add("enum-hit hwnd="+h+" class="+cn+" title='"+t+"' vis="+IsWindowVisible(h));
         }
         return true;
     }, IntPtr.Zero);
     var fw = FindWindowW("AllPeriphPanel", null);
     sbOut.Add("FindWindow(class)="+fw);
     return string.Join("\n", sbOut.ToArray());
 }
}
"@
[W8]::Report()
