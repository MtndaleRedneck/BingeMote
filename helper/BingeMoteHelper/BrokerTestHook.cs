#if BROKER_TEST_HOOK
using System.IO.Pipes;
using System.Text;

namespace BingeMoteHelper;

/// <summary>
/// CI-only broker pipe client behaviors, compiled into the e2e test build only
/// (DefineConstants includes BROKER_TEST_HOOK there; the release exe has no trace).
/// Because this runs from the same exe image as the helper, the broker's PipePeer
/// client check accepts it - which is exactly what makes it useful for testing:
///   --broker-hold <seconds>          connect and hold the connection silently: an
///                                    authorized but wedged client. The broker must keep
///                                    serving other connections and idle-drop this one.
///   --broker-flood <count> <outfile> send <count> move ops as fast as possible and
///                                    write a one-line result to <outfile>: the broker
///                                    must rate-limit and drop the connection.
/// </summary>
internal static class BrokerTestHook
{
    internal static int Run(string[] args)
    {
        try
        {
            var hold = args.Contains("--broker-hold");
            int ArgAfter(string flag)
            {
                var i = Array.IndexOf(args, flag);
                return i >= 0 && i + 1 < args.Length ? int.Parse(args[i + 1]) : 0;
            }
            using var pipe = new NamedPipeClientStream(".", InputBroker.PipeName, PipeDirection.InOut, PipeOptions.Asynchronous);
            pipe.Connect(5000);
            PipePeer.VerifyServer(pipe);
            if (hold)
            {
                Thread.Sleep(TimeSpan.FromSeconds(Math.Max(ArgAfter("--broker-hold"), 1)));
                return 0;
            }
            var count = Math.Max(ArgAfter("--broker-flood"), 1);
            var outFile = args.Length > Array.IndexOf(args, "--broker-flood") + 2 ? args[Array.IndexOf(args, "--broker-flood") + 2] : "broker-flood.txt";
            using var writer = new StreamWriter(pipe, new UTF8Encoding(false), 4096, leaveOpen: true) { AutoFlush = true };
            using var reader = new StreamReader(pipe, Encoding.UTF8, false, 4096, leaveOpen: true);
            var sent = 0;
            var last = "";
            var disconnected = false;
            try
            {
                for (; sent < count; sent++)
                {
                    writer.WriteLine("m 1 0");
                    var reply = reader.ReadLine();
                    last = reply ?? "(closed)";
                    if (reply == null || reply.StartsWith("err", StringComparison.Ordinal))
                    {
                        if (reply != null) sent++;
                        disconnected = reply == null;
                        break;
                    }
                }
            }
            catch (IOException) { disconnected = true; }
            File.WriteAllText(outFile, $"sent={sent} last=\"{last}\" disconnected={disconnected}");
            return 0;
        }
        catch (Exception ex)
        {
            try
            {
                var i = Array.IndexOf(args, "--broker-flood");
                if (i >= 0 && i + 2 < args.Length) File.WriteAllText(args[i + 2], "hook_error=" + ex.GetType().Name + ": " + ex.Message);
            }
            catch { }
            return 1;
        }
    }
}
#endif
