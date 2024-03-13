package com.github.theprez.appinstall;

import java.beans.PropertyVetoException;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.UnsupportedEncodingException;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarInputStream;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.zip.ZipEntry;

import org.yaml.snakeyaml.Yaml;

import com.github.theprez.jcmdutils.AppLogger;
import com.github.theprez.jcmdutils.ProcessLauncher;
import com.github.theprez.jcmdutils.StringUtils;
import com.ibm.as400.access.AS400;
import com.ibm.as400.access.AS400Message;
import com.ibm.as400.access.AS400SecurityException;
import com.ibm.as400.access.CommandCall;
import com.ibm.as400.access.ErrorCompletingRequestException;
import com.ibm.as400.access.Job;
import com.ibm.as400.access.JobLog;
import com.ibm.as400.access.ObjectDoesNotExistException;
import com.ibm.as400.access.QueuedMessage;

public class InstallPackageBuilder {
    static synchronized void runCommand(final AppLogger _logger, final AS400 _as400, final String _cmd, final boolean _isOkToFail) throws IOException, ObjectDoesNotExistException, PropertyVetoException { // TODO: where should this
        // live?
        try {
            _logger.printfln_verbose("Running CL command '%s'", _cmd);
            final CommandCall cmd = new CommandCall(_as400);
            cmd.setMessageOption(AS400Message.MESSAGE_OPTION_ALL);
            cmd.getServerJob().setLoggingCLPrograms(Job.LOG_CL_PROGRAMS_YES);
            JobLog jobLog = cmd.getServerJob().getJobLog();
            jobLog.load();
            int jobLogPos = jobLog.getLength();
            final boolean isSuccess = cmd.run(_cmd);

            final AS400Message[] msgs = cmd.getMessageList();
            for (final AS400Message msg : msgs) {
                if (StringUtils.isEmpty(msg.getID())) {
                    _logger.printfln("    %s", msg.getText());
                }
            }
            try {
                jobLog = cmd.getServerJob().getJobLog();
                jobLog.load();
                final QueuedMessage[] jobLogMsgs = jobLog.getMessages(jobLogPos, jobLog.getLength());
                for (final QueuedMessage jobLogMsg : jobLogMsgs) {
                    if(AS400Message.INFORMATIONAL == jobLogMsg.getType()) {
                        continue;
                    }
                    _logger.printfln("    %s: %s", jobLogMsg.getID(), jobLogMsg.getText());
                }
            } catch (final Exception e) {
                _logger.exception(e);
            }
            if (!isSuccess && !_isOkToFail) {
                throw new IOException("Error running command");
            }
        } catch (AS400SecurityException | ErrorCompletingRequestException | InterruptedException e) {
            throw new IOException("Error running command", e);
        }
    }

    private static void xfer(final InputStream _in, final OutputStream _out, final int _bufferSize) throws IOException {
        final byte[] buf = new byte[_bufferSize];
        int bytesRead = -1;
        while (0 < (bytesRead = _in.read(buf))) {
            _out.write(buf, 0, bytesRead);
        }
    }

    private final File m_dir;
    private final Set<File> m_files = new TreeSet<File>();
    private final Set<String> m_libraries = new TreeSet<String>();

    private final AppLogger m_logger;

    private File m_outputFile = null;
	private String m_lodrunLib;

    public InstallPackageBuilder(final AppLogger _logger) {
        m_logger = _logger;
        final String installId = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        final File homeDir = new File(System.getProperty("user.home", "~"));
        final File dotDir = new File(homeDir, ".appinstall");
        final File allBuildsDir = new File(dotDir, "builds");
        m_dir = new File(allBuildsDir, installId);
    }

    public void addBareDirectory(final String _dir) throws IOException {
        final File dir = verifyDir(_dir);
        m_files.add(dir);
    }

    public void addFile(final File _f) throws IOException {
        final File f = verifyExists(_f);
        if (f.isDirectory()) {
            m_files.add(f);
            for (final File child : f.listFiles()) {
                addFile(child);
            }
        } else {
            m_files.add(f);
        }
    }

    public void addFile(final String _f) throws IOException {
        addFile(new File(_f));
    }

    public void addFromSpecFile(final AppLogger _logger, final String _specFile) throws UnsupportedEncodingException, FileNotFoundException, IOException {
        try (BufferedReader br = new BufferedReader(new InputStreamReader(new FileInputStream(_specFile), "UTF-8"))) {
            throw new IOException("not implemented");
        }
    }

    public void addLibrary(final String _lib) throws IOException {
        final String trimmedUpper = _lib.trim().toUpperCase();
        verifyExists("/qsys.lib/" + trimmedUpper + ".lib");
        if (trimmedUpper.startsWith("Q")) {
            throw new IOException("Libraries starting with 'Q' are not allowed");
        }
        m_libraries.add(trimmedUpper);
    }

    public void setLodrunLib(final AppLogger _logger, final String _lib) {
    	m_lodrunLib = _lib;
    }

    public void build() throws IOException, URISyntaxException, InterruptedException, ObjectDoesNotExistException, PropertyVetoException {
        if (null == m_outputFile) {
            throw new IOException("Output file not specified");
        } // TODO: check if exists
        m_logger.printfln_verbose("Building package in temp dir '%s'", m_dir.getAbsolutePath());
        m_dir.mkdirs();
        if (!m_dir.isDirectory()) {
            throw new IOException("Could not create temporary installation directory: " + m_dir);
        }
        final List<String> manifestFiles = new LinkedList<String>();
        final List<String> manifestCommands = new LinkedList<String>();

        // package up stream files
        if (!m_files.isEmpty()) {
            m_logger.println("Saving stream files...");
            final File tarFile = new File(m_dir, "files.tar");
            boolean isCreating = true;
            for (final File file : m_files) {
                m_logger.printfln_verbose("Packaging file '%s'...", file.getAbsolutePath());
                final Process p = Runtime.getRuntime().exec(new String[] { "/QOpenSys/usr/bin/tar", "-D", isCreating ? "-cvf" : "-uvf", tarFile.getName(), file.getAbsolutePath() }, new String[] { "QIBM_PASE_DESCRIPTOR_STDIO=B" }, m_dir);
                ProcessLauncher.pipeStreamsToCurrentProcess("TAR", p, m_logger);
                if(0 !=p.waitFor()) {
                    throw new IOException("Error packaging stream files");
                }
                isCreating = false;
            }
            manifestFiles.add(tarFile.getName());
            // Assume lodrun installs files
            if (m_lodrunLib==null) {
            	final String untarCmd = "/QOpenSys/usr/bin/tar xvf files.tar -C /.";
	            manifestCommands.add(untarCmd);
            }
        }

        final AS400 as400 = new AS400();
        try {
            // package up libraries
            for (final String library : m_libraries) {
                m_logger.printfln("Saving library %s...", library);
                runCommand(as400, "CRTSAVF QTEMP/" + library, false);
                runCommand(as400, "SAVLIB LIB(" + library + ") DEV(*SAVF) SAVF(QTEMP/" + library + ")", false);
                final File stmf = new File(m_dir, library + ".lib");
                runCommand(as400, "CPYTOSTMF FROMMBR('/qsys.lib/qtemp.lib/" + library + ".file') TOSTMF('" + stmf.getAbsolutePath() + "') STMFOPT(*REPLACE) CVTDTA(*NONE) ENDLINFMT(*FIXED)", false);
                manifestFiles.add(stmf.getName());
                
                manifestCommands.add("CRTSAVF QTEMP/" + library);
                manifestCommands.add("CPYFRMSTMF FROMSTMF('$PWD/" + stmf.getName() + "') TOMBR('/qsys.lib/qtemp.lib/" + library + ".file') MBROPT(*REPLACE) CVTDTA(*NONE) ENDLINFMT(*FIXED) TABEXPN(*NO)");
                // Assume lodrun installs libraries
                if (m_lodrunLib==null) {
	                // TODO: conditionally DLTLIB first
	                manifestCommands.add("DLTLIB " + library);
	                manifestCommands.add("RSTLIB SAVLIB(" + library + ") DEV(*SAVF) SAVF(QTEMP/" + library + ") MBROPT(*ALL) ALWOBJDIF(*ALL) RSTLIB(" + library + ")");
                }
            }
            
            // Create LODRUN QINSTAPP save file
            if (m_lodrunLib != null) {
                m_logger.printfln("Creating QINSTAPP save file...");
                runCommand(as400, "CRTSAVF QTEMP/QINSTAPP", false);
            	// SAVOBJ/RSTOBJ QINSTAPP to QTEMP if needed (instead of CRTDUPOBJ to preserve attributes)
                if (!"QTEMP".equalsIgnoreCase(m_lodrunLib)) {
                    runCommand(as400, "SAVOBJ OBJ(QINSTAPP) OBJTYPE(*PGM) DEV(*SAVF) SAVF(QTEMP/QINSTAPP) LIB(" + m_lodrunLib + ')', false);
                    runCommand(as400, "RSTOBJ OBJ(QINSTAPP) OBJTYPE(*PGM) DEV(*SAVF) SAVF(QTEMP/QINSTAPP) RSTLIB(QTEMP) ALWOBJDIF(*ALL) MBROPT(*ALL) SAVLIB(" + m_lodrunLib + ')', false);
                    runCommand(as400, "CLRSAVF QTEMP/QINSTAPP", false);
                }
                runCommand(as400, "SAVOBJ OBJ(QINSTAPP) OBJTYPE(*PGM) DEV(*SAVF) SAVF(QTEMP/QINSTAPP) LIB(QTEMP)", false);;
                final File stmf = new File(m_dir, "qinstapp.pgm");
                runCommand(as400, "CPYTOSTMF FROMMBR('/qsys.lib/qtemp.lib/qinstapp.file') TOSTMF('" + stmf.getAbsolutePath() + "') CVTDTA(*NONE) ENDLINFMT(*FIXED)", false);
                manifestFiles.add(stmf.getName());
                
                manifestCommands.add("CRTSAVF QTEMP/QINSTAPP");
                manifestCommands.add("CPYFRMSTMF FROMSTMF('$PWD/" + stmf.getName() + "') TOMBR('/qsys.lib/qtemp.lib/qinstapp.file') MBROPT(*REPLACE) CVTDTA(*NONE) ENDLINFMT(*FIXED) TABEXPN(*NO)");
                manifestCommands.add("LODRUN DEV(*SAVF) SAVF(QTEMP/QINSTAPP)");
            }
        } finally {
            as400.disconnectAllServices();
        }

        // find our own jar file....
        final URL self = m_logger.getClass().getProtectionDomain().getCodeSource().getLocation();

        // now, to create the actual package....
        m_logger.println("Building final package...");
        final Manifest mf = new Manifest();
        mf.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        mf.getMainAttributes().put(Attributes.Name.MAIN_CLASS, AppInstall.class.getCanonicalName());
        try (JarOutputStream out = new JarOutputStream(new FileOutputStream(m_outputFile), mf)) {
            // copy ourselves
            final JarInputStream me = new JarInputStream(new FileInputStream(new File(self.toURI())));
            ZipEntry myEntry = null;
            while (null != (myEntry = me.getNextEntry())) {
                if (myEntry.getName().startsWith("APPINSTALL-INF")) {
                    continue;
                }
                final JarEntry newEntry = new JarEntry(myEntry.getName());
                out.putNextEntry(newEntry);
                xfer(me, out, 1024 * 512);
                out.closeEntry();
            }
            m_logger.println_verbose("done copying ourselves");
            // write our manifest data
            final ZipEntry manifestEntry = new ZipEntry("APPINSTALL-INF/manifest.yml");
            final Map<String, Object> yamlData = new LinkedHashMap<String, Object>();
            yamlData.put("files", manifestFiles);
            yamlData.put("commands", manifestCommands);
            final OutputStreamWriter yamlWriter = new OutputStreamWriter(out);
            out.putNextEntry(manifestEntry);
            new Yaml().dump(yamlData, yamlWriter);
            yamlWriter.flush();
            out.closeEntry();
            m_logger.println_verbose("done adding our manifest");

            // add each file
            for (final String l : manifestFiles) {
                final ZipEntry fileEntry = new ZipEntry("APPINSTALL-DATA/" + l);
                out.putNextEntry(fileEntry);
                final File sourceFile = new File(m_dir, l);
                try (FileInputStream fis = new FileInputStream(sourceFile)) {
                    xfer(fis, out, 1024 * 1024);
                }
                out.closeEntry();
            }
            m_logger.println_success("Successfully created install package: " + m_outputFile.getAbsolutePath());
        }
    }

    private void runCommand(final AS400 _as400, final String _cmd, final boolean _isOkToFail) throws IOException, ObjectDoesNotExistException, PropertyVetoException {
        runCommand(m_logger, _as400, _cmd, _isOkToFail);
    }

    public void setOutputFile(final String _f) throws IOException {
        if (null != m_outputFile) {
            throw new IOException("Output file set more than once");
        }
        m_outputFile = new File(_f);
    }

    private File verifyDir(final String _path) throws IOException {
        final File f = new File(_path);
        if (!f.isDirectory()) {
            throw new IOException("File '" + f.getAbsolutePath() + "' is not a directory");
        }
        return f.getCanonicalFile();
    }

    private File verifyExists(final File _f) throws IOException {
        if (!_f.exists()) {
            throw new IOException("File '" + _f.getAbsolutePath() + "' does not exist");
        }
        return _f.getCanonicalFile();
    }

    private File verifyExists(final String _path) throws IOException {
        return verifyExists(new File(_path));
    }
}
