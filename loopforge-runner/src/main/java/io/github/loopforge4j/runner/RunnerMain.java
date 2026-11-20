package io.github.loopforge4j.runner;

/** {@code java -jar loopforge-runner-<version>-all.jar}; configuration in {@link RunnerConfig}. */
public final class RunnerMain {

    private RunnerMain() {}

    public static void main(String[] args) throws Exception {
        RunnerConfig config;
        try {
            config = RunnerConfig.fromEnvironment(System.getenv());
        } catch (IllegalArgumentException e) {
            System.err.println("loopforge-runner: " + e.getMessage());
            System.err.println("set LOOPFORGE_SERVER_URL and LOOPFORGE_RUNNER_TOKEN (from the console: 执行机器 → 注册)");
            System.exit(2);
            return;
        }
        RunnerAgent agent = new RunnerAgent(config).start();
        Runtime.getRuntime().addShutdownHook(new Thread(agent::close, "runner-shutdown"));
        Thread.currentThread().join();
    }
}
