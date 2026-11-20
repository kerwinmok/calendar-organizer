using System;
using System.Diagnostics;
using System.Drawing;
using System.IO;
using System.IO.Compression;
using System.Text;
using System.Threading;
using System.Windows.Forms;

namespace CalendarOrganizerLauncher {

    internal static class Program {

        private const string Magic = "CALORG01";
        private const int FooterSize = 40;
        private const string AppExeName = "CalendarOrganizer.exe";

        [STAThread]
        private static int Main(string[] args) {
            try {
                string launcherPath = typeof(Program).Assembly.Location;
                PayloadInfo info = ReadFooter(launcherPath);
                string cacheRoot = ResolveCacheRoot();
                string appDir = Path.Combine(cacheRoot, "app-" + info.Stamp);
                string readyMarker = Path.Combine(appDir, ".ready");
                string innerExe = Path.Combine(appDir, AppExeName);

                if (File.Exists(readyMarker) && File.Exists(innerExe)) {
                    LaunchApp(innerExe, launcherPath, args);
                    return 0;
                }

                using (Mutex extractLock = new Mutex(false, "Local\\CalendarOrganizer-Extract-" + info.Stamp)) {
                    try {
                        extractLock.WaitOne();
                    } catch (AbandonedMutexException) {
                    }

                    try {
                        if (!File.Exists(readyMarker) || !File.Exists(innerExe)) {
                            ExtractWithSplash(launcherPath, info, cacheRoot, appDir);
                        }
                    } finally {
                        try {
                            extractLock.ReleaseMutex();
                        } catch {
                        }
                    }
                }

                LaunchApp(innerExe, launcherPath, args);
                return 0;
            } catch (Exception ex) {
                MessageBox.Show(
                    "Calendar Organizer could not start.\n\n" + ex.Message,
                    "Calendar Organizer",
                    MessageBoxButtons.OK,
                    MessageBoxIcon.Error
                );
                return 1;
            }
        }

        private static string ResolveCacheRoot() {
            string envLocal = Environment.GetEnvironmentVariable("LOCALAPPDATA");
            if (!string.IsNullOrWhiteSpace(envLocal)) {
                return Path.Combine(envLocal, "CalendarOrganizer");
            }
            string localFolder = Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData);
            if (!string.IsNullOrWhiteSpace(localFolder)) {
                return Path.Combine(localFolder, "CalendarOrganizer");
            }
            return Path.Combine(Path.GetTempPath(), "CalendarOrganizer");
        }

        private static PayloadInfo ReadFooter(string exePath) {
            using (FileStream fs = new FileStream(exePath, FileMode.Open, FileAccess.Read, FileShare.ReadWrite | FileShare.Delete)) {
                long fileLen = fs.Length;
                if (fileLen < FooterSize) {
                    throw new InvalidOperationException("Application payload footer is missing.");
                }
                fs.Seek(-FooterSize, SeekOrigin.End);
                byte[] footer = new byte[FooterSize];
                int read = fs.Read(footer, 0, FooterSize);
                if (read != FooterSize) {
                    throw new InvalidOperationException("Could not read application payload footer.");
                }

                string magic = Encoding.ASCII.GetString(footer, 32, 8);
                if (!string.Equals(magic, Magic, StringComparison.Ordinal)) {
                    throw new InvalidOperationException("Invalid application payload header.");
                }

                string stamp = Encoding.ASCII.GetString(footer, 0, 16);
                if (!IsHexStamp(stamp)) {
                    throw new InvalidOperationException("Corrupted application stamp.");
                }

                long offset = BitConverter.ToInt64(footer, 16);
                long length = BitConverter.ToInt64(footer, 24);
                if (offset <= 0 || length <= 0 || offset + length + FooterSize != fileLen) {
                    throw new InvalidOperationException("Application payload bounds are invalid.");
                }
                return new PayloadInfo(stamp, offset, length);
            }
        }

        private static bool IsHexStamp(string text) {
            if (text == null || text.Length != 16) {
                return false;
            }
            for (int i = 0; i < text.Length; i++) {
                char ch = text[i];
                bool digit = ch >= '0' && ch <= '9';
                bool lowerHex = ch >= 'a' && ch <= 'f';
                if (!digit && !lowerHex) {
                    return false;
                }
            }
            return true;
        }

        private static void ExtractWithSplash(string launcherPath, PayloadInfo info, string cacheRoot, string appDir) {
            Directory.CreateDirectory(cacheRoot);
            Application.EnableVisualStyles();
            Exception workerError = null;

            using (SplashForm splash = new SplashForm()) {
                splash.Shown += delegate {
                    Thread worker = new Thread(delegate() {
                        try {
                            ExtractArchive(launcherPath, info, cacheRoot, appDir, splash);
                        } catch (Exception ex) {
                            workerError = ex;
                        } finally {
                            try {
                                splash.BeginInvoke(new MethodInvoker(splash.Close));
                            } catch {
                            }
                        }
                    });
                    worker.IsBackground = true;
                    worker.Start();
                };
                Application.Run(splash);
            }

            if (workerError != null) {
                throw new InvalidOperationException(workerError.Message, workerError);
            }
        }

        private static void ExtractArchive(string launcherPath, PayloadInfo info, string cacheRoot, string appDir, SplashForm splash) {
            string tempDir = Path.Combine(cacheRoot, "app-" + info.Stamp + ".tmp-" + Process.GetCurrentProcess().Id);
            SafeDeleteDir(tempDir);
            Directory.CreateDirectory(tempDir);

            try {
                string fullTempRoot = Path.GetFullPath(tempDir);
                if (!fullTempRoot.EndsWith(Path.DirectorySeparatorChar.ToString())) {
                    fullTempRoot = fullTempRoot + Path.DirectorySeparatorChar;
                }

                //reads zip payload directly from the pe overlay without buffering into ram
                using (FileStream fs = new FileStream(launcherPath, FileMode.Open, FileAccess.Read, FileShare.ReadWrite | FileShare.Delete))
                using (BoundedStream bounded = new BoundedStream(fs, info.Offset, info.Length))
                using (ZipArchive archive = new ZipArchive(bounded, ZipArchiveMode.Read)) {
                    int total = Math.Max(1, archive.Entries.Count);
                    int done = 0;
                    int lastPercent = -1;

                    foreach (ZipArchiveEntry entry in archive.Entries) {
                        string rel = entry.FullName.Replace('/', Path.DirectorySeparatorChar).Replace('\\', Path.DirectorySeparatorChar);
                        string targetPath = Path.GetFullPath(Path.Combine(tempDir, rel));
                        if (!targetPath.StartsWith(fullTempRoot, StringComparison.OrdinalIgnoreCase)) {
                            throw new InvalidOperationException("Unsafe archive path: " + entry.FullName);
                        }

                        if (string.IsNullOrEmpty(entry.Name) || rel.EndsWith(Path.DirectorySeparatorChar.ToString())) {
                            Directory.CreateDirectory(targetPath);
                        } else {
                            string parent = Path.GetDirectoryName(targetPath);
                            if (!string.IsNullOrEmpty(parent)) {
                                Directory.CreateDirectory(parent);
                            }
                            using (Stream src = entry.Open())
                            using (FileStream dst = File.Create(targetPath)) {
                                src.CopyTo(dst);
                            }
                        }

                        done++;
                        int pct = (done * 100) / total;
                        if (pct != lastPercent) {
                            lastPercent = pct;
                            splash.UpdateProgress(pct);
                        }
                    }
                }

                File.WriteAllText(Path.Combine(tempDir, ".ready"), info.Stamp, Encoding.ASCII);
                if (Directory.Exists(appDir)) {
                    SafeDeleteDir(appDir);
                }
                Directory.Move(tempDir, appDir);
                CleanupStaleVersions(cacheRoot, appDir);
            } catch {
                SafeDeleteDir(tempDir);
                throw;
            }
        }

        private static void CleanupStaleVersions(string cacheRoot, string activeAppDir) {
            try {
                string activeFull = Path.GetFullPath(activeAppDir);
                string[] dirs = Directory.GetDirectories(cacheRoot, "app-*");
                foreach (string dir in dirs) {
                    string full = Path.GetFullPath(dir);
                    if (!string.Equals(full, activeFull, StringComparison.OrdinalIgnoreCase)) {
                        SafeDeleteDir(dir);
                    }
                }
            } catch {
            }
        }

        private static void SafeDeleteDir(string path) {
            try {
                if (Directory.Exists(path)) {
                    Directory.Delete(path, true);
                }
            } catch {
            }
        }

        private static void LaunchApp(string innerExe, string launcherPath, string[] args) {
            string exeDir = Path.GetDirectoryName(launcherPath);
            ProcessStartInfo psi = new ProcessStartInfo();
            psi.FileName = innerExe;
            psi.Arguments = BuildArguments(args);
            psi.UseShellExecute = false;
            if (!string.IsNullOrWhiteSpace(exeDir) && Directory.Exists(exeDir)) {
                psi.WorkingDirectory = exeDir;
            }

            string existingHome = Environment.GetEnvironmentVariable("CALENDAR_ORGANIZER_HOME");
            if (string.IsNullOrWhiteSpace(existingHome) && !string.IsNullOrWhiteSpace(exeDir)) {
                string portableMarker = Path.Combine(exeDir, "portable.txt");
                string portableDataDir = Path.Combine(exeDir, "calendar-organizer-data");
                if (File.Exists(portableMarker) || Directory.Exists(portableDataDir)) {
                    psi.EnvironmentVariables["CALENDAR_ORGANIZER_HOME"] = portableDataDir;
                }
            }

            Process.Start(psi);
        }

        private static string BuildArguments(string[] args) {
            if (args == null || args.Length == 0) {
                return "";
            }
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < args.Length; i++) {
                if (i > 0) {
                    sb.Append(' ');
                }
                sb.Append(QuoteArgument(args[i]));
            }
            return sb.ToString();
        }

        private static string QuoteArgument(string arg) {
            if (arg == null) {
                return "\"\"";
            }
            bool needsQuotes = arg.Length == 0;
            for (int i = 0; i < arg.Length && !needsQuotes; i++) {
                if (char.IsWhiteSpace(arg[i]) || arg[i] == '"') {
                    needsQuotes = true;
                }
            }
            if (!needsQuotes) {
                return arg;
            }

            StringBuilder sb = new StringBuilder();
            sb.Append('"');
            int backslashes = 0;
            for (int i = 0; i < arg.Length; i++) {
                char c = arg[i];
                if (c == '\\') {
                    backslashes++;
                } else if (c == '"') {
                    sb.Append('\\', backslashes * 2 + 1);
                    sb.Append('"');
                    backslashes = 0;
                } else {
                    if (backslashes > 0) {
                        sb.Append('\\', backslashes);
                        backslashes = 0;
                    }
                    sb.Append(c);
                }
            }
            if (backslashes > 0) {
                sb.Append('\\', backslashes * 2);
            }
            sb.Append('"');
            return sb.ToString();
        }

        private sealed class PayloadInfo {
            public readonly string Stamp;
            public readonly long Offset;
            public readonly long Length;

            public PayloadInfo(string stamp, long offset, long length) {
                this.Stamp = stamp;
                this.Offset = offset;
                this.Length = length;
            }
        }

        private sealed class BoundedStream : Stream {
            private readonly Stream inner;
            private readonly long offset;
            private readonly long length;
            private long position;

            public BoundedStream(Stream inner, long offset, long length) {
                this.inner = inner;
                this.offset = offset;
                this.length = length;
                this.position = 0;
                this.inner.Seek(offset, SeekOrigin.Begin);
            }

            public override bool CanRead {
                get { return true; }
            }

            public override bool CanSeek {
                get { return true; }
            }

            public override bool CanWrite {
                get { return false; }
            }

            public override long Length {
                get { return length; }
            }

            public override long Position {
                get { return position; }
                set { Seek(value, SeekOrigin.Begin); }
            }

            public override int Read(byte[] buffer, int bufferOffset, int count) {
                long remaining = length - position;
                if (remaining <= 0 || count <= 0) {
                    return 0;
                }
                int toRead = count;
                if (remaining < toRead) {
                    toRead = (int)remaining;
                }
                if (inner.Position != offset + position) {
                    inner.Seek(offset + position, SeekOrigin.Begin);
                }
                int read = inner.Read(buffer, bufferOffset, toRead);
                position = position + read;
                return read;
            }

            public override long Seek(long delta, SeekOrigin origin) {
                long target = position;
                if (origin == SeekOrigin.Begin) {
                    target = delta;
                } else if (origin == SeekOrigin.Current) {
                    target = position + delta;
                } else if (origin == SeekOrigin.End) {
                    target = length + delta;
                }
                if (target < 0) {
                    target = 0;
                } else if (target > length) {
                    target = length;
                }
                position = target;
                inner.Seek(offset + position, SeekOrigin.Begin);
                return position;
            }

            public override void Flush() {
            }

            public override void SetLength(long value) {
                throw new NotSupportedException();
            }

            public override void Write(byte[] buffer, int bufferOffset, int count) {
                throw new NotSupportedException();
            }
        }

        private sealed class SplashForm : Form {
            private int percent;

            public SplashForm() {
                FormBorderStyle = FormBorderStyle.None;
                StartPosition = FormStartPosition.CenterScreen;
                ShowInTaskbar = true;
                TopMost = true;
                ClientSize = new Size(380, 128);
                BackColor = Color.FromArgb(0x1c, 0x1c, 0x1c);
                DoubleBuffered = true;
                Text = "Calendar Organizer";
            }

            public void UpdateProgress(int nextPercent) {
                int clamped = Math.Max(0, Math.Min(100, nextPercent));
                if (IsDisposed) {
                    return;
                }
                if (InvokeRequired) {
                    try {
                        BeginInvoke((Action)delegate() {
                            percent = clamped;
                            Invalidate();
                        });
                    } catch {
                    }
                } else {
                    percent = clamped;
                    Invalidate();
                }
            }

            protected override void OnPaint(PaintEventArgs e) {
                base.OnPaint(e);
                Graphics g = e.Graphics;

                using (Pen border = new Pen(Color.FromArgb(0x3c, 0x3c, 0x3c), 1f)) {
                    g.DrawRectangle(border, 0, 0, ClientSize.Width - 1, ClientSize.Height - 1);
                }

                using (Font titleFont = new Font("Segoe UI", 13f, FontStyle.Bold))
                using (Font subFont = new Font("Segoe UI", 9.5f, FontStyle.Regular))
                using (SolidBrush titleBrush = new SolidBrush(Color.FromArgb(0xe2, 0xe2, 0xe2)))
                using (SolidBrush subBrush = new SolidBrush(Color.FromArgb(0x8e, 0x8e, 0x8e))) {
                    g.DrawString("Calendar Organizer", titleFont, titleBrush, 24f, 22f);
                    g.DrawString("Preparing first launch...", subFont, subBrush, 24f, 52f);
                }

                int barX = 24;
                int barY = 86;
                int barW = ClientSize.Width - 48;
                int barH = 10;
                using (SolidBrush trackBrush = new SolidBrush(Color.FromArgb(0x2d, 0x2d, 0x2d)))
                using (SolidBrush fillBrush = new SolidBrush(Color.FromArgb(0x01, 0x65, 0xdd))) {
                    g.FillRectangle(trackBrush, barX, barY, barW, barH);
                    int fillW = (barW * percent) / 100;
                    if (fillW > 0) {
                        g.FillRectangle(fillBrush, barX, barY, fillW, barH);
                    }
                }
            }
        }
    }
}
