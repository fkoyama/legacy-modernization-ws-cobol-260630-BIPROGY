package com.practicebank.pipeline.console;

import com.practicebank.pipeline.core.FatalPipelineException;
import com.practicebank.pipeline.core.InvalidInputException;
import com.practicebank.pipeline.core.IoFailureException;
import com.practicebank.pipeline.core.Status;
import com.practicebank.pipeline.orchestrator.TxnPipelineOrchestrator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.ExitCodeGenerator;
import org.springframework.stereotype.Component;

/**
 * Controller layer: parses the command line, runs the orchestrator and maps the
 * outcome to a process exit code (OK/NO_INPUT=0, PARTIAL=4, INVALID=8,
 * IO_FAIL=12, FATAL=16). Usage errors exit 8.
 */
@Component
public class ConsoleRunner implements CommandLineRunner, ExitCodeGenerator {

    private static final Logger log = LoggerFactory.getLogger(ConsoleRunner.class);
    private static final String USAGE =
            "usage: run-daily --batch-id <id> --business-date <YYYYMMDD|YYYY-MM-DD> "
                    + "--input <path> [--source-system <name>]";

    private final PipelineCommandParser parser;
    private final TxnPipelineOrchestrator orchestrator;
    private int exitCode = Status.OK.exitCode();

    public ConsoleRunner(PipelineCommandParser parser, TxnPipelineOrchestrator orchestrator) {
        this.parser = parser;
        this.orchestrator = orchestrator;
    }

    @Override
    public void run(String... args) {
        try {
            PipelineCommand cmd = parser.parse(args);
            Status status = orchestrator.runCore(cmd);
            exitCode = status.exitCode();
        } catch (IllegalArgumentException e) {
            log.error("invalid arguments: {}\n{}", e.getMessage(), USAGE);
            exitCode = Status.INVALID.exitCode();
        } catch (InvalidInputException e) {
            log.error("pipeline aborted (INVALID): {}", e.getMessage());
            exitCode = Status.INVALID.exitCode();
        } catch (IoFailureException e) {
            log.error("pipeline aborted (IO_FAIL): {}", e.getMessage());
            exitCode = Status.IO_FAIL.exitCode();
        } catch (FatalPipelineException e) {
            log.error("pipeline aborted (FATAL): {}", e.getMessage());
            exitCode = Status.FATAL.exitCode();
        } catch (RuntimeException e) {
            log.error("pipeline aborted (FATAL, unexpected)", e);
            exitCode = Status.FATAL.exitCode();
        }
    }

    @Override
    public int getExitCode() {
        return exitCode;
    }
}
