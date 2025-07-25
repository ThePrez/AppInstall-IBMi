package com.github.theprez.appinstall;

import java.beans.PropertyVetoException;
import java.io.File;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import com.github.theprez.jcmdutils.AppLogger;
import com.github.theprez.jcmdutils.ConsoleQuestionAsker;
import com.github.theprez.jcmdutils.AppLogger.DefaultLogger;
import com.github.theprez.jcmdutils.ProcessLauncher.ProcessResult;
import com.github.theprez.jcmdutils.StringUtils.TerminalColor;
import com.github.theprez.jcmdutils.ProcessLauncher;
import com.github.theprez.jcmdutils.StringUtils;
import com.ibm.as400.access.AS400;
import com.ibm.as400.access.ObjectDoesNotExistException;

public class InstallationTask {

    private final PackageConfiguration m_config;
    private final File m_dir;
    private final AppLogger m_logger;

    public InstallationTask(final AppLogger _logger, final PackageConfiguration _config, final File _sourceDir) {
        m_logger = _logger;
        m_dir = _sourceDir;
        m_config = _config;
    }

    public void run(InstallOptions installOptions)
            throws IOException, InterruptedException, ObjectDoesNotExistException, PropertyVetoException {
        final DefaultLogger childLogger = new DefaultLogger(true);
        {
            final File preinstall = new File(m_dir, ".preinstall");
            if (preinstall.exists()) {
                m_logger.println("Executing pre-installation tasks...");
                preinstall.setExecutable(true);
                final Process p = Runtime.getRuntime().exec(preinstall.getName(), null, m_dir);
                ProcessLauncher.pipeStreamsToCurrentProcess("PREINSTALL", p, childLogger);
                p.waitFor();
                if (0 != p.exitValue()) {
                    throw new IOException("Pre-installation tasks failed");
                }
                m_logger.println_success("Successfully executed pre-installation tasks");
            }
        }
        final AS400 as400 = new AS400();
        try {
            m_logger.println("Performing installation...");
            for (final String cmd : inferCommandsFromFileList(m_config.getFiles(), installOptions)) {
                if (StringUtils.isEmpty(cmd)) {
                    continue;
                }
                if (Character.isUpperCase(cmd.trim().charAt(0))) { // CL command
                    final String doctoredCmd = cmd.replace("$PWD", m_dir.getAbsolutePath());
                    InstallPackageBuilder.runCommand(m_logger, as400, doctoredCmd,
                            cmd.trim().toUpperCase().startsWith("DLT"));
                } else {
                    m_logger.printfln_verbose("Running command '%s'", cmd);
                    final Process p = Runtime.getRuntime().exec(new String[] { "/QOpenSys/usr/bin/sh", "-c", cmd },
                            null, m_dir);
                    ProcessLauncher.pipeStreamsToCurrentProcess("INSTALL", p, childLogger);
                    p.waitFor();
                    if (0 != p.exitValue()) {
                        throw new IOException("Installation tasks failed");
                    }
                }
            }
        } finally {
            if (null != as400) {
                as400.disconnectAllServices();
            }
        }
        final File postinstall = new File(m_dir, ".postinstall");

        if (postinstall.exists()) {
            m_logger.println("Executing post-installation tasks...");
            postinstall.setExecutable(true);
            final Process p = Runtime.getRuntime().exec(postinstall.getName(), null, m_dir);
            ProcessLauncher.pipeStreamsToCurrentProcess("POSTINSTALL", p, childLogger);
            p.waitFor();
            if (0 != p.exitValue()) {
                throw new IOException("Pre-installation tasks failed");
            }
            m_logger.println_success("Successfully executed post-installation tasks");
        }
        m_logger.println_success("Installation complete");
    }

    /**
     * @param files
     * @param _confirm Specify 'y' to always confirm, specify 'c' to confirm only
     *                 non-delete actions
     */
    private List<String> inferCommandsFromFileList(List<String> files, InstallOptions installOptions)
            throws UnsupportedEncodingException, IOException {
        List<String> manifestCommands = new LinkedList<String>();
        String confirmationMsg = "\n\n\nIf you continue, the following actions will be taken on your system:\n\n";
        for (String file : files) {
            if (file.endsWith(".tar")) {
                // Restore file action
                File tarFile = new File(m_dir, file);

                ProcessResult tarInfolist = ProcessLauncher
                        .exec("/QOpenSys/usr/bin/tar tvf " + tarFile.getAbsolutePath());
                if (0 != tarInfolist.getExitStatus()) {
                    throw new IOException("Error processing saved stream file data");
                }
                ProcessResult tarPathList = ProcessLauncher
                        .exec("/QOpenSys/usr/bin/tar tf " + tarFile.getAbsolutePath());
                if (0 != tarPathList.getExitStatus()) {
                    throw new IOException("Error processing saved stream file data");
                }

                List<String> infoList = tarInfolist.getStdout();
                List<String> pathList = tarPathList.getStdout();

                if (infoList.size() != pathList.size()) {
                    throw new IOException("Mismatch between tar info and file list outputs");
                }

                Map<String, String> willInstall = new LinkedHashMap<>();
                Map<String, String> willNotInstall = new LinkedHashMap<>();
                Map<String, String> willOverwrite = new LinkedHashMap<>();

                for (int i = 0; i < infoList.size(); i++) {
                    String info = infoList.get(i);
                    String path = pathList.get(i);

                    File destFile = new File(path);

                    if (destFile.exists()) {
                        if (file.endsWith("IfMissing.tar")) {
                            willNotInstall.put(path, info);
                        } else {
                            willOverwrite.put(path, info);
                        }
                    } else {
                        willInstall.put(path, info);
                    }
                }

                if (!willInstall.isEmpty()) {
                    confirmationMsg += StringUtils.colorizeForTerminal(
                            "  - The following stream files will be installed:\n", TerminalColor.YELLOW);
                    for (String path : willInstall.values()) {
                        confirmationMsg += "        " + StringUtils.colorizeForTerminal(path, TerminalColor.CYAN)
                                + "\n";
                    }
                    confirmationMsg += "\n";
                }

                if (!willOverwrite.isEmpty()) {
                    confirmationMsg += StringUtils.colorizeForTerminal(
                            "  - The following stream files will be deleted from the system and replaced with the version included in this bundle:\n",
                            TerminalColor.BRIGHT_RED);
                    for (String path : willOverwrite.values()) {
                        confirmationMsg += "        " + StringUtils.colorizeForTerminal(path, TerminalColor.CYAN)
                                + "\n";
                    }
                    confirmationMsg += "\n";
                }

                if (!willNotInstall.isEmpty()) {
                    confirmationMsg += StringUtils.colorizeForTerminal(
                            "  - The following stream files already exist and will not be installed:\n",
                            TerminalColor.PURPLE);
                    for (String path : willNotInstall.values()) {
                        confirmationMsg += "        " + StringUtils.colorizeForTerminal(path, TerminalColor.CYAN)
                                + "\n";
                    }
                    confirmationMsg += "\n";
                }

                // Construct untar command
                String untarCmd = "/QOpenSys/usr/bin/tar xvf " + tarFile.getAbsolutePath() + " -C /.";
                if (!willNotInstall.isEmpty()) {
                    for (String path : willInstall.keySet()) {
                        untarCmd += " " + path;
                    }
                }
                manifestCommands.add(untarCmd);
            } else if (file.endsWith(".lib")) {
                // Restore library action
                String savlib = file.replace(".lib", "").trim();
                String rstlib = installOptions.rstlib != null ? installOptions.rstlib : savlib;
                if (!installOptions.lodrun && libraryExists(rstlib)) {
                    confirmationMsg += StringUtils.colorizeForTerminal(
                            "  - Library " + rstlib.toUpperCase()
                                    + " will be deleted from the system and replaced with the version included in this bundle\n\n",
                            TerminalColor.BRIGHT_RED);
                } else {
                    confirmationMsg += StringUtils.colorizeForTerminal("  - Library " + rstlib.toUpperCase() +
                            " will be installed on the system\n\n", TerminalColor.YELLOW);
                }

                manifestCommands.add("CRTSAVF QTEMP/" + savlib);
                manifestCommands.add("CPYFRMSTMF FROMSTMF('$PWD/" + file + "') TOMBR('/qsys.lib/qtemp.lib/" + savlib
                        + ".file') MBROPT(*REPLACE) CVTDTA(*NONE) ENDLINFMT(*FIXED) TABEXPN(*NO)");
                if (!installOptions.lodrun) {
                    manifestCommands.add("DLTLIB " + rstlib);
                    String rstlibCmd = "RSTLIB SAVLIB(" + savlib + ") DEV(*SAVF) SAVF(QTEMP/" + savlib
                            + ") MBROPT(*ALL) ALWOBJDIF(*ALL) RSTLIB(" + rstlib + ')';
                    if (installOptions.rstasp != null)
                        rstlibCmd += " RSTASP(" + installOptions.rstasp + ')';
                    if (installOptions.rstaspdev != null)
                        rstlibCmd += " RSTASPDEV(" + installOptions.rstaspdev + ')';
                    manifestCommands.add(rstlibCmd);
                }
            } else if (file.equals("qinstapp.pgm")) {
                // LODRUN action
                if (installOptions.lodrun) {
                    confirmationMsg += StringUtils.colorizeForTerminal(
                            "  - LODRUN DEV(*SAVF) SAVF(QTEMP/QINSTAPP) will be run:\n", TerminalColor.YELLOW);
                    manifestCommands.add("CRTSAVF QTEMP/QINSTAPP");
                    manifestCommands.add("CPYFRMSTMF FROMSTMF('$PWD/" + file
                            + "') TOMBR('/qsys.lib/qtemp.lib/qinstapp.file') MBROPT(*REPLACE) CVTDTA(*NONE) ENDLINFMT(*FIXED) TABEXPN(*NO)");
                    manifestCommands.add("LODRUN DEV(*SAVF) SAVF(QTEMP/QINSTAPP)");
                }
            }
        }
        System.out.println(confirmationMsg);
        if (installOptions.confirm == 'y' || (installOptions.confirm == 'c' && !confirmationMsg.contains("delete"))) {
            m_logger.println_warn("Continuing without confirmation");
        } else {
            ConsoleQuestionAsker asker = new ConsoleQuestionAsker();
            boolean reply = asker.askBooleanQuestion(m_logger, "N", "Are you sure you want to proceed? (y/N)",
                    (Object) null);
            if (!reply) {
                throw new IOException("Canceled by user");
            }
        }
        return manifestCommands;
    }

    private static boolean libraryExists(String _library) {
        return new File("/qsys.lib/" + _library + ".lib").exists();
    }

}
