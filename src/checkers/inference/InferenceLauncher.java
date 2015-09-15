package checkers.inference;


import checkers.inference.InferenceOptions.InitStatus;
import org.checkerframework.framework.test.TestUtilities;
import org.checkerframework.framework.util.CheckerMain;
import org.checkerframework.framework.util.ExecUtil;
import org.checkerframework.framework.util.PluginUtil;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * InferenceLauncher functions as the launch script for the inference framework.
 InferenceLauncher can be run in 4 modes:

 1) TYPECHECK: Run an inference checker in typecheck mode without inferring any annotations.

 2) INFER: Run an inference checker in inference mode.  Inference is run by spawning a new Java process
 using InferenceMain as the main class. Inference builds a set of constraints, runs a solver
 (which solves or serializes the previously built constraints), and outputs a JAIF file which can be used
 to re-insert the solved constraints.

 3) ROUNDTRIP: Run inference (from mode 2) and then insert solved constraints. While isnertion can be done
 using a previously output JAIF file and the Annotation File Utilities, the InferenceLauncher will allow you
 to insert the annotations immediately after inference.

 4) ROUNDTRIP_TYPECHECK: This first runs all of the steps in ROUNDTRIP mode then runs typechecking
 over the newly updated code.

 See InferenceOptions for descriptions of the various options that can be passed to InferenceLauncher.
 */
public class InferenceLauncher {

    public static void main(String [] args) {
        new InferenceLauncher(System.out, System.err).launch(args);
    }

    //used to redirect output when executing processes
    private final PrintStream outStream;
    private final PrintStream errStream;

    public InferenceLauncher(PrintStream outStream, PrintStream errStream) {
        this.outStream = outStream;
        this.errStream = errStream;
    }

    /**
     * Parses and validates the input arguments using InferenceOptions then executes the appropriate
     * phases for the various launch modes.
     */
    public void launch(String [] args) {
        InitStatus initStatus = InferenceOptions.init(args, true);
        initStatus.validateOrExit();

        Mode mode = null;
        try {
            mode = Mode.valueOf(InferenceOptions.mode);

        } catch (IllegalArgumentException iexc) {
            outStream.println("Could not recognize mode: " + InferenceOptions.mode + "\n"
                    + "valid modes: " + PluginUtil.join(", ", Mode.values()));
            System.exit(1);
        }

        switch (mode) {
            case TYPECHECK:
                typecheck(InferenceOptions.javaFiles);
                break;

            case INFER:
                infer();
                break;

            case ROUNDTRIP:
                infer();
                insertJaif();
                break;

            case ROUNDTRIP_TYPECHECK:
                infer();
                List<String> updatedJavaFiles =  insertJaif();
                typecheck(updatedJavaFiles.toArray(new String[updatedJavaFiles.size()]));
                break;
        }
    }

    public enum Mode {
        //just run typechecking do not infer anything
        TYPECHECK,

        //run inference but do not typecheck or insert the result into source code
        INFER,

        //run inference and insert the result back into source code
        ROUNDTRIP,

        //run inference, insert the result back into source code, and typecheck
        ROUNDTRIP_TYPECHECK
    }

    /**
     * Run typechecking on the input java files using the options in InferenceOptions
     */
    public void typecheck(String [] javaFiles) {
        printStep("Typechecking", outStream);

        final int initialOptsLength = 2 + (InferenceOptions.debug != null ? 2 : 0);

        String [] options;
        options = new String[initialOptsLength + InferenceOptions.javacOptions.length + javaFiles.length];
        options[0] = "-processor";
        options[1] = InferenceOptions.checker;

        if (InferenceOptions.debug != null) {
            options[2] = "-J-Xdebug";
            options[3] = "-J-Xrunjdwp:transport=dt_socket,server=y,suspend=y,address=" + InferenceOptions.debug;
        }

        System.arraycopy(InferenceOptions.javacOptions, 0, options, initialOptsLength, InferenceOptions.javacOptions.length);
        System.arraycopy(javaFiles, 0, options, InferenceOptions.javacOptions.length + initialOptsLength, javaFiles.length);

        final CheckerMain checkerMain = new CheckerMain(InferenceOptions.checkerJar, options);
        checkerMain.addToRuntimeBootclasspath(getInferenceRuntimeBootJars());

        if (InferenceOptions.printCommands) {
            outStream.println("Running typecheck command:");
            outStream.println(PluginUtil.join(" ", checkerMain.getExecArguments()));
        }

        int result = checkerMain.invokeCompiler();

        reportStatus("Typechecking", result, outStream);
        outStream.flush();
        exitOnNonZeroStatus(result);
    }

    /**
     * Run inference using the Java files and options in InferenceOptions.
     */
    public void infer() {
        printStep("Inferring", outStream);
        final String java = PluginUtil.getJavaCommand(System.getProperty("java.home"), outStream);
        List<String> argList = new LinkedList<>();
        argList.add(java);
        argList.addAll(getMemoryArgs());

        if (InferenceOptions.debug != null) {
            argList.add("-agentlib:jdwp=transport=dt_socket,server=y,suspend=y,address=" + InferenceOptions.debug);
        }

        argList.add(getInferenceRuntimeBootclassPath());
        argList.addAll(
                Arrays.asList(
                        "-ea", "-ea:checkers.inference...",
                        "checkers.inference.InferenceMain",
                        "--checker", InferenceOptions.checker)
        );

        addIfNotNull("--jaifFile", InferenceOptions.jaifFile, argList);
        addIfNotNull("--logLevel", InferenceOptions.logLevel, argList);
        addIfNotNull("--solver", InferenceOptions.solver, argList);
        addIfNotNull("--solverArgs", InferenceOptions.solverArgs, argList);

        addIfTrue("--hacks", InferenceOptions.hacks, argList);

        argList.add("--");
        argList.add(getInferenceCompilationBootclassPath());
        int preJavacOptsSize = argList.size();
        argList.addAll(Arrays.asList(InferenceOptions.javacOptions));

        //The options that come after -- are not passed to the Java process that runs
        //the Checker Framework and therefore should not contain -Xmx or -Xms
        //TODO: We may be able to remove this call because of the getMemoryArgs method (called above)
        removeMemoryArgsInRange(argList, preJavacOptsSize, argList.size());

        argList.addAll(Arrays.asList(InferenceOptions.javaFiles));

        if (InferenceOptions.printCommands) {
            outStream.println("Running infer command:");
            outStream.println(PluginUtil.join(" ", argList));
        }

        int result = ExecUtil.execute(argList.toArray(new String[argList.size()]), outStream, System.err);
        outStream.flush();
        errStream.flush();

        reportStatus("Inference", result, outStream);
        outStream.flush();
        exitOnNonZeroStatus(result);
    }

    private void removeMemoryArgsInRange(List<String> argList, int preJavacOptsSize, int postJavacOptsSize) {
        for (int i = preJavacOptsSize; i < argList.size() && i < postJavacOptsSize; /*incremented-below*/) {
            String current = argList.get(i);
            if (current.startsWith("-Xmx") || current.startsWith("-Xms")) {
                argList.remove(i);
            } else {
                ++i;
            }
        }
    }

    /**
     * Using the options InferenceOptions.afuOutputDir and InferenceOptions.inPlace,
     * insert the JAIF output by the infer step into the source code either in place or
     * copied to InferenceOptions.afuOutputDir.
     * TODO: Honor InferenceOptions.afuOptions, which is used no where in this method
     */
    public List<String> insertJaif() {
        List<String> outputJavaFiles = new ArrayList<>(InferenceOptions.javaFiles.length);

        printStep("Inserting annotations", outStream);
        int result;
        if (!InferenceOptions.inPlace) {
            final File outputDir = new File(InferenceOptions.afuOutputDir);
            TestUtilities.ensureDirectoryExists(outputDir);

            String jaifFile = getJaifFilePath(outputDir);

            String [] options = new String [5 + InferenceOptions.javaFiles.length];
            options[0] = "insert-annotations-to-source";
            options[1] = "-v";
            options[2] = "-d";
            options[3] = outputDir.getAbsolutePath();
            options[4] = jaifFile;

            System.arraycopy(InferenceOptions.javaFiles, 0, options, 5, InferenceOptions.javaFiles.length);

            if (InferenceOptions.printCommands) {
                outStream.println("Running Insert Annotations Command:");
                outStream.println(PluginUtil.join(" ", options));
            }

            //this can get quite large for large projects and it is not advisable to run
            //roundtripping via the InferenceLauncher for these projects
            ByteArrayOutputStream insertOut = new ByteArrayOutputStream();
            result = ExecUtil.execute(options, insertOut, errStream);
            outStream.println(insertOut.toString());


            List<File> newJavaFiles = findWrittenFiles(insertOut.toString());
            for (File newJavaFile : newJavaFiles) {
                outputJavaFiles.add(newJavaFile.getAbsolutePath());
            }

        } else {
            String jaifFile = getJaifFilePath(new File("."));

            String [] options = new String [5 + InferenceOptions.javaFiles.length];
            options[0] = "insert-annotations-to-source";
            options[1] = "-v";
            options[2] = "-i";
            options[4] = jaifFile;

            System.arraycopy(InferenceOptions.javaFiles, 0, options, 5, InferenceOptions.javaFiles.length);

            if (InferenceOptions.printCommands) {
                outStream.println("Running Insert Annotations Command:");
                outStream.println(PluginUtil.join(" ", options));
            }

            result = ExecUtil.execute(options, outStream, errStream);

            for (String filePath : InferenceOptions.javaFiles) {
                outputJavaFiles.add(filePath);
            }
        }

        reportStatus("Insert annotations", result, outStream);
        outStream.flush();
        exitOnNonZeroStatus(result);
        return outputJavaFiles;
    }

    /**
     * This method scrapes the output of the AFU for new Java file locations which
     * are later used in typechecking.
     */
    private static List<File> findWrittenFiles(String output) {
        //This will be brittle; if the AFU Changes it's output string then no files will be found
        final Pattern afuWritePattern = Pattern.compile("^Writing (.*\\.java)$");

        List<File> writtenFiles = new ArrayList<>();
        BufferedReader reader = new BufferedReader(new StringReader(output));
        String line;

        do {
            try {
                line = reader.readLine();
                if (line != null) {
                    Matcher afuWriteMatcher = afuWritePattern.matcher(line);
                    if (afuWriteMatcher.matches()) {
                        writtenFiles.add(new File(afuWriteMatcher.group(1)));
                    }
                }

            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
        while (line != null);
        return writtenFiles;
    }

    private static String getJaifFilePath(File outputDir) {

        String jaifFile = InferenceOptions.jaifFile;
        if (jaifFile == null) {
            jaifFile = new File(outputDir, "inference.jaif").getAbsolutePath();
        }

        return jaifFile;
    }

    /**
     * @return The -Xmx and -Xms arguments that should be used to run the infer process.
     */
    private static List<String> getMemoryArgs() {
        //this should instead read them from InferenceOptions and fall back to this if they are not present
        //perhaps just find all -J
        String xmx = "-Xmx2048m";
        String xms = "-Xms512m";
        for (String javacOpt : InferenceOptions.javacOptions) {
            if (javacOpt.startsWith("-Xms") || javacOpt.startsWith("-J-Xms")) {
                xms = javacOpt;
            } else if (javacOpt.startsWith("-Xmx") || javacOpt.startsWith("-J-Xmx")) {
                xmx = javacOpt;
            }
        }

        return Arrays.asList(xms, xmx);
    }

    /**
     * @return the jar files that should be placed on the runtime bootclasspath of the process
     * that runs inference.
     */
    public static List<String> getInferenceRuntimeBootJars() {
        final File distDir = InferenceOptions.pathToThisJar.getParentFile();
        String jdkJarName = PluginUtil.getJdkJarName();

        List<String> filePaths = new ArrayList<>();
        for (File child : distDir.listFiles()) {
            String name = child.getName();
            if (!name.endsWith(jdkJarName)) {
                filePaths.add(child.getAbsolutePath());
            }
        }

        return filePaths;
    }

    /**
     * @return the bootclasspath that should used to run the inference process.
     */
    public static String getInferenceRuntimeBootclassPath() {
        List<String> filePaths = getInferenceRuntimeBootJars();
        return "-Xbootclasspath/p:" + PluginUtil.join(File.pathSeparator, filePaths);
    }

    /**
     * @return the bootclasspath that should be compiled against.  Note, javac always compiles
     * against this bootclasspath AND the runtime bootclasspath of the process running javac.
     */
    public static String getInferenceCompilationBootclassPath() {
        String jdkJarName = PluginUtil.getJdkJarName();
        final File jdkFile = new File(InferenceOptions.pathToThisJar.getParentFile(), jdkJarName);

        return "-Xbootclasspath/p:" + jdkFile.getAbsolutePath();
    }


    public static void printStep(String step, PrintStream out) {
        out.println("\n--- " + step + " ---" + "\n");
    }

    public static void reportStatus(String prefix, int returnCode, PrintStream out) {
        out.println("\n--- " + prefix + (returnCode == 0 ? " succeeded" : " failed") + " ---" + "\n");
    }

    public static void exitOnNonZeroStatus(int result) {
        if (result != 0) {
            System.exit(result);
        }
    }

    public static void addIfTrue(String name, boolean isPresent, List<String> args) {
        if (isPresent) {
            args.add(name);
        }
    }

    public static void addIfNotNull(String name, String option, List<String> args) {
        if (option != null && !option.isEmpty()) {
            args.add(name);
            args.add(option);
        }
    }
}
