import org.junit.platform.engine.TestExecutionResult;
import org.junit.platform.engine.discovery.DiscoverySelectors;
import org.junit.platform.launcher.*;
import org.junit.platform.launcher.core.*;
import org.junit.platform.launcher.listeners.SummaryGeneratingListener;
import java.io.*;
import java.nio.file.*;
import java.util.*;

/** Runs selected test classes/methods and records one line per test to a result file. */
public class RunSelected {
    public static void main(String[] args) throws Exception {
        Path out = Path.of(args[0]);
        List<org.junit.platform.engine.DiscoverySelector> selectors = new ArrayList<>();
        for (int i = 1; i < args.length; i++) {
            String a = args[i];
            selectors.add(a.contains("#") ? DiscoverySelectors.selectMethod(a) : DiscoverySelectors.selectClass(a));
        }
        List<String> lines = Collections.synchronizedList(new ArrayList<>());
        TestExecutionListener recorder = new TestExecutionListener() {
            final Map<String, Long> start = new HashMap<>();
            @Override public void executionStarted(TestIdentifier id) { start.put(id.getUniqueId(), System.nanoTime()); }
            @Override public void executionSkipped(TestIdentifier id, String reason) {
                if (id.isTest()) lines.add("SKIPPED | " + id.getDisplayName() + " | " + reason);
            }
            @Override public void executionFinished(TestIdentifier id, TestExecutionResult result) {
                if (!id.isTest()) {
                    if (result.getStatus() != TestExecutionResult.Status.SUCCESSFUL)
                        lines.add("CONTAINER-" + result.getStatus() + " | " + id.getDisplayName() + " | " + reason(result));
                    return;
                }
                long ms = (System.nanoTime() - start.getOrDefault(id.getUniqueId(), System.nanoTime())) / 1_000_000;
                String cls = id.getSource().map(Object::toString).orElse("");
                cls = cls.replaceAll(".*className='([^']+)'.*methodName='([^']+)'.*", "$1#$2");
                lines.add(result.getStatus() + " | " + ms + "ms | " + id.getDisplayName() + " | " + cls.replace("com.huawei.ascend.sit.", "") + " | " + reason(result));
            }
            private String reason(TestExecutionResult r) {
                return r.getThrowable().map(t -> {
                    String m = t.getClass().getSimpleName() + ": " + String.valueOf(t.getMessage());
                    for (StackTraceElement e : t.getStackTrace()) {
                        if (e.getClassName().startsWith("com.huawei.ascend.sit.")) {
                            m += " @ " + e.getClassName().replaceAll(".*\\.", "") + ":" + e.getLineNumber();
                            break;
                        }
                    }
                    return m.replace('\n', ' ').replace('\r', ' ');
                }).orElse("");
            }
        };
        SummaryGeneratingListener summary = new SummaryGeneratingListener();
        Launcher launcher = LauncherFactory.create();
        launcher.execute(LauncherDiscoveryRequestBuilder.request().selectors(selectors).build(), recorder, summary);
        var s = summary.getSummary();
        lines.add(String.format("SUMMARY | found=%d started=%d succeeded=%d failed=%d aborted=%d skipped=%d containersFailed=%d",
                s.getTestsFoundCount(), s.getTestsStartedCount(), s.getTestsSucceededCount(), s.getTestsFailedCount(),
                s.getTestsAbortedCount(), s.getTestsSkippedCount(), s.getContainersFailedCount()));
        Files.write(out, lines);
        lines.forEach(System.out::println);
        System.exit(s.getTotalFailureCount() == 0 && s.getTestsStartedCount() > 0 ? 0 : 1);
    }
}
