# AppInstall-IBMi
Universal application installer for IBM i



To build an application installation image:
```fortran
Usage: java -jar <jarfile> -o <package_file> [options] [[component]...]

  <package_file> is the file name of the build package you are creating
    (.jar file extension is preferred)

   Valid options include:
       -v          : verbose mode
       --version   : print version information
       -h/--help   : print this help

  Multiple components can be specified. These identify components
  of the application for which you are creating an installer.
    Valid component values include:
        --qsys <library>   : a library in the QSYS.LIB file system
        --dir <dir>        : a directory (contents are not included)
        --file <file/dir>  : a file or directory (if a directory, contents are included)
        --spec <file>      : a specification file listing application components
```

The resulting file is a runnable `.jar` file that will install the application!

