# JITWatch Plugin for IntelliJ IDEA

The plugin can be used to view and analyze HotSpot JIT compilation logs inside IntelliJ IDEA.
It uses [JITWatch](https://github.com/AdoptOpenJDK/jitwatch) to load and analyze the logs and provides a UI for exploring the compilation data directly in your code editor.

## Creating and Loading the Compilation Log

The simple option for creating and loading the compilation log is to enable the "Log compilation" option in the "JITWatch" tab of the run configuration settings.

If you enable the option, the plugin will create a HotSpot log in a temporary directory and automatically load it after the execution completes.

Alternatively, you can add the logging options to the VM options of your run configuration, and then load the log file manually using the Analyze | Load HotSpot Compilation Log... menu item. To enable logging, you need the following options:

    -XX:+UnlockDiagnosticVMOptions
    -XX:+TraceClassLoading
    -XX:+LogCompilation

The plugin does not support viewing the assembly code at this time, so you shouldn't enable the `-XX:+PrintAssembly` option.

  
