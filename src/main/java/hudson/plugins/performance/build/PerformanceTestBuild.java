package hudson.plugins.performance.build;

import hudson.Extension;
import hudson.FilePath;
import hudson.Launcher;
import hudson.model.AbstractProject;
import hudson.model.Result;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.plugins.performance.Messages;
import hudson.tasks.BuildStepDescriptor;
import hudson.tasks.Builder;
import jenkins.tasks.SimpleBuildStep;
import org.jenkinsci.Symbol;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;

import javax.annotation.Nonnull;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * "Build step" for running performance test
 */
public class PerformanceTestBuild extends Builder implements SimpleBuildStep {

    protected final static String PERFORMANCE_TEST_COMMAND = "bzt";
    protected final static String VIRTUALENV_COMMAND = "virtualenv";
    protected final static String HELP_COMMAND = "--help";
    protected final static String VIRTUALENV_PATH = "taurus-venv/bin/";
    protected final static String[] CHECK_BZT_COMMAND = new String[]{PERFORMANCE_TEST_COMMAND, HELP_COMMAND};
    protected final static String[] CHECK_VIRTUALENV_BZT_COMMAND = new String[]{VIRTUALENV_PATH + PERFORMANCE_TEST_COMMAND, HELP_COMMAND};
    protected final static String[] CHECK_VIRTUALENV_COMMAND = new String[]{VIRTUALENV_COMMAND, HELP_COMMAND};
    protected final static String[] CREATE_LOCAL_PYTHON_COMMAND = new String[]{VIRTUALENV_COMMAND, "--clear", "--system-site-packages", "taurus-venv"};
    protected final static String[] INSTALL_BZT_COMMAND = new String[]{VIRTUALENV_PATH + "pip", "--no-cache-dir", "install", PERFORMANCE_TEST_COMMAND};
    protected final static String DEFAULT_CONFIG_FILE = "defaultReport.yml";


    @Symbol("performanceTest")
    @Extension
    public static class Descriptor extends BuildStepDescriptor<Builder> {

        @Override
        public boolean isApplicable(Class<? extends AbstractProject> jobType) {
            return true;
        }

        @Override
        public String getDisplayName() {
            return Messages.PerformanceTest_Name();
        }
    }


    private String params;

    @DataBoundConstructor
    public PerformanceTestBuild(String params) throws IOException {
        this.params = params;
    }


    @Override
    public void perform(@Nonnull Run<?, ?> run, @Nonnull FilePath workspace, @Nonnull Launcher launcher, @Nonnull TaskListener listener) throws InterruptedException, IOException {
        PrintStream logger = listener.getLogger();
        boolean isVirtualenvInstallation = false;
        boolean isBztInstalled = false;


        logger.println("Performance test: Checking bzt installed on your machine.");
        // Step 1: Check bzt using "bzt --help".
        if (!runCmd(CHECK_BZT_COMMAND, workspace, logger, true)) {
            logger.println("Performance test: You have not got bzt on your machine. Next step is checking virtualenv.");
            // Step 1.1: If bzt not installed check virtualenv using "virtualenv --help".
            if (runCmd(CHECK_VIRTUALENV_COMMAND, workspace, logger, true)) {
                logger.println("Performance test: Checking virtualenv is OK. Next step is creation local python.");
                // Step 1.2: Create local python using "virtualenv --clear --system-site-packages taurus-venv".
                if (runCmd(CREATE_LOCAL_PYTHON_COMMAND, workspace, logger, true)) {
                    logger.println("Performance test: Creation local python is OK. Next step is install bzt.");
                    // Step 1.3: Install bzt in virtualenv using "taurus-venv/bin/pip install bzt".
                    if (runCmd(INSTALL_BZT_COMMAND, workspace, logger, true)) {
                        logger.println("Performance test: bzt installed successfully. Checking bzt.");
                        // Step 1.4: Check bzt using "taurus-venv/bin/bzt --help"
                        if (runCmd(CHECK_VIRTUALENV_BZT_COMMAND, workspace, logger, true)) {
                            logger.println("Performance test: bzt is working.");
                            isVirtualenvInstallation = true;
                        }
                    }
                }
            }
        } else {
            logger.println("Performance test: bzt is installed on your machine.");
            isBztInstalled = true;
        }

        if (isBztInstalled || isVirtualenvInstallation) {
            // Step 2: Run performance test.
            String[] params = this.params.split(" ");
            final List<String> testCommand = new ArrayList<String>(params.length + 2);
            testCommand.add((isVirtualenvInstallation ? VIRTUALENV_PATH : "") + PERFORMANCE_TEST_COMMAND);
            for (String param : params) {
                if (!param.isEmpty()) {
                    testCommand.add(param);
                }
            }
            testCommand.add(extractDefaultReportToWorkspace(workspace));

            if (runCmd(testCommand.toArray(new String[testCommand.size()]), workspace, logger, false)) {
                run.setResult(Result.SUCCESS);
                return;
            }
        }

        run.setResult(Result.FAILURE);
    }

    public static boolean runCmd(String[] commands, FilePath workspace, final PrintStream logger, boolean skipOutput) throws IOException {
        ProcessBuilder processBuilder = new ProcessBuilder(commands);
        processBuilder.directory(new File(workspace.getRemote()));

        final Process process;
        try {
            process = processBuilder.start();
        } catch (IOException e) {
            if (!skipOutput) {
                e.printStackTrace(logger);
            }
            return false;
        }


        int code;
        try {
            if (!skipOutput) {
                new Thread() {
                    @Override
                    public void run() {
                        printStreamToLogger(process.getInputStream(), logger);
                    }
                }.start();
            }
            process.getOutputStream().close();
            code = process.waitFor();
        } catch (InterruptedException e) {
            if (!skipOutput) {
                e.printStackTrace(logger);
                printStreamToLogger(process.getErrorStream(), logger);
            }
            return false;
        }


        if (code != 0 && !skipOutput) {
            printStreamToLogger(process.getErrorStream(), logger);
        }

        return code == 0;
    }

    protected static void printStreamToLogger(InputStream source, PrintStream target) {
        BufferedReader input = new BufferedReader(new InputStreamReader(source));
        String line;

        try {
            while ((line = input.readLine()) != null)
                target.println(line);
        } catch (IOException e) {
            target.println("Reading of error stream caused next exception: " + e.getMessage());
        }
    }


    protected String extractDefaultReportToWorkspace(FilePath workspace) throws IOException, InterruptedException {
        FilePath defaultConfig = workspace.child(DEFAULT_CONFIG_FILE);
        defaultConfig.copyFrom(getClass().getResourceAsStream(DEFAULT_CONFIG_FILE));
        return defaultConfig.getRemote();
    }

    public String getParams() {
        return params;
    }

    @DataBoundSetter
    public void setParams(String params) {
        this.params = params;
    }
}
