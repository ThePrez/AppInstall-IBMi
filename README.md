# AppInstall-IBMi
Universal application installer for IBM i



To build an application installation image:
```fortran
Usage: java -jar appinstall-ibmi-xxx.jar -o <package_file> [options] [[component]...]

  <package_file> is the file name of the build package you are creating
    (.jar file extension is preferred)

   Valid options include:
       -v          : verbose mode
       --version   : print version information
       -h/--help   : print this help
       --lodrun <library> : save QINSTAPP program from <library> to be used by LODRUN, below
       --spec <file>      : a specification file listing application components

  Multiple components can be specified. These identify components
  of the application for which you are creating an installer.
    Valid component values include:
        --qsys <library>   : a library in the QSYS.LIB file system
        --dir  <dir>        : a directory (contents are not included)
        --file <file/dir>  : a file or directory (if a directory, contents are included)
        --pre  <file>      : a pre-install script (only one can be specified)
        --post <file>      : a post-install script (only one can be specified)
        --spec <file>      : a specification file listing application components
```


The resulting file is a runnable `.jar` file that will install the application!
```fortran
Usage: java -jar <package_file> [option]
   Valid options include:
       -v          : verbose mode
       -y          : automatically reply 'Y' to all confirmation (install/delete) messages
       -c          : automatically reply 'Y' to all non-destructive (install) confirmation messages
       -l/--lodrun : do LODRUN instead of directly restoring (if specified, the following --rstxxx options are ignored)
       --rstlib <library>   : override restore library
       --rstasp <asp>       : override restore asp
       --rstaspdev <aspdev> : override restore asp device
``` 


