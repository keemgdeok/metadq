package io.github.keemgdeok.metadq.cli;

import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Spec;

@Command(
    name = "metadq",
    description = "Conservative data-quality evidence from Apache Iceberg metadata.",
    mixinStandardHelpOptions = true,
    version = "metadq 0.1.1",
    subcommands = {DoctorCommand.class, CheckCommand.class})
public final class Metadq implements Runnable {
  @Spec private CommandSpec spec;

  @Override
  public void run() {
    spec.commandLine().usage(spec.commandLine().getOut());
  }

  public static CommandLine createCommandLine() {
    return new CommandLine(new Metadq()).setCaseInsensitiveEnumValuesAllowed(true);
  }

  public static void main(String[] args) {
    int exitCode = createCommandLine().execute(args);
    System.exit(exitCode);
  }
}
