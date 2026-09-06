# Mengirim berkas .prn apa adanya ke printer Windows.
#
# Perintah cetak biasa akan melewati driver dan mengubah isinya. Di sini
# dipakai winspool langsung dengan datatype "RAW", sehingga byte ESC/P-R
# sampai ke printer persis seperti yang dihasilkan encoder -- sama seperti
# yang nanti dikirim aplikasi Android lewat USB.
#
# Pemakaian:
#   powershell -File tools\send_raw.ps1 -Printer "EPSON L3110 Series" -File uji.prn

param(
    [Parameter(Mandatory = $true)][string]$Printer,
    [Parameter(Mandatory = $true)][string]$File
)

$ErrorActionPreference = "Stop"
$path = (Resolve-Path $File).Path

Add-Type @"
using System;
using System.IO;
using System.Runtime.InteropServices;

public static class RawPrinter
{
    [StructLayout(LayoutKind.Sequential, CharSet = CharSet.Unicode)]
    public class DocInfo
    {
        [MarshalAs(UnmanagedType.LPWStr)] public string pDocName;
        [MarshalAs(UnmanagedType.LPWStr)] public string pOutputFile;
        [MarshalAs(UnmanagedType.LPWStr)] public string pDataType;
    }

    [DllImport("winspool.Drv", EntryPoint = "OpenPrinterW", SetLastError = true, CharSet = CharSet.Unicode)]
    static extern bool OpenPrinter(string src, out IntPtr hPrinter, IntPtr pd);

    [DllImport("winspool.Drv", EntryPoint = "ClosePrinter", SetLastError = true)]
    static extern bool ClosePrinter(IntPtr hPrinter);

    [DllImport("winspool.Drv", EntryPoint = "StartDocPrinterW", SetLastError = true, CharSet = CharSet.Unicode)]
    static extern int StartDocPrinter(IntPtr hPrinter, int level, [In, MarshalAs(UnmanagedType.LPStruct)] DocInfo di);

    [DllImport("winspool.Drv", EntryPoint = "EndDocPrinter", SetLastError = true)]
    static extern bool EndDocPrinter(IntPtr hPrinter);

    [DllImport("winspool.Drv", EntryPoint = "StartPagePrinter", SetLastError = true)]
    static extern bool StartPagePrinter(IntPtr hPrinter);

    [DllImport("winspool.Drv", EntryPoint = "EndPagePrinter", SetLastError = true)]
    static extern bool EndPagePrinter(IntPtr hPrinter);

    [DllImport("winspool.Drv", EntryPoint = "WritePrinter", SetLastError = true)]
    static extern bool WritePrinter(IntPtr hPrinter, IntPtr pBytes, int dwCount, out int dwWritten);

    public static string Send(string printerName, string filePath, string jobName)
    {
        byte[] data = File.ReadAllBytes(filePath);

        IntPtr hPrinter;
        if (!OpenPrinter(printerName, out hPrinter, IntPtr.Zero))
            return "OpenPrinter gagal, kode " + Marshal.GetLastWin32Error();

        try
        {
            DocInfo di = new DocInfo();
            di.pDocName = jobName;
            di.pOutputFile = null;
            di.pDataType = "RAW";

            int jobId = StartDocPrinter(hPrinter, 1, di);
            if (jobId == 0)
                return "StartDocPrinter gagal, kode " + Marshal.GetLastWin32Error();

            if (!StartPagePrinter(hPrinter))
                return "StartPagePrinter gagal, kode " + Marshal.GetLastWin32Error();

            IntPtr buffer = Marshal.AllocCoTaskMem(data.Length);
            try
            {
                Marshal.Copy(data, 0, buffer, data.Length);
                int written;
                bool ok = WritePrinter(hPrinter, buffer, data.Length, out written);
                int err = Marshal.GetLastWin32Error();

                EndPagePrinter(hPrinter);
                EndDocPrinter(hPrinter);

                if (!ok) return "WritePrinter gagal, kode " + err;
                if (written != data.Length)
                    return "Hanya " + written + " dari " + data.Length + " byte terkirim";
                return "OK job=" + jobId + " bytes=" + written;
            }
            finally { Marshal.FreeCoTaskMem(buffer); }
        }
        finally { ClosePrinter(hPrinter); }
    }
}
"@

$size = (Get-Item $path).Length
Write-Host ("Mengirim {0:N0} byte ke '{1}' sebagai data RAW..." -f $size, $Printer)
$result = [RawPrinter]::Send($Printer, $path, "Uji ESCPR")
Write-Host $result
if (-not $result.StartsWith("OK")) { exit 1 }
