import java.io.File;
import java.io.IOException;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.PrintStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

import java.lang.reflect.Method;
import java.io.InputStream;
import java.io.ByteArrayInputStream;
import java.io.SequenceInputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.net.URI;
import java.net.URLClassLoader;
import java.net.URL;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;
import java.util.Map;
import java.util.jar.JarEntry;

public class ExecJruby {

  static boolean setSystemProperty(final String name, final String value) {
      try {
          System.setProperty(name, value);
          return true;
      }
      catch (SecurityException e) {
          return false;
      }
  }

  static protected void initJRubyScriptingEnv(Object scriptingContainer) throws Exception {
      // for some reason, the container needs to run a scriptlet in order for it
      // to be able to find the gem executables later
      invokeMethod(scriptingContainer, "runScriptlet", "SCRIPTING_CONTAINER_INITIALIZED=true");

      invokeMethod(scriptingContainer, "setHomeDirectory", "uri:classloader:/META-INF/jruby.home");
  }

  protected static Object invokeMethod(final Object self, final String name, final Object... args)
      throws NoSuchMethodException, IllegalAccessException, Exception {

      final Class[] signature = new Class[args.length];
      for ( int i = 0; i < args.length; i++ ) signature[i] = args[i].getClass();
      return invokeMethod(self, name, signature, args);
  }

  protected static Object invokeMethod(final Object self, final String name, final Class[] signature, final Object... args)
      throws NoSuchMethodException, IllegalAccessException, Exception {
      Method method = self.getClass().getDeclaredMethod(name, signature);
      try {
          return method.invoke(self, args);
      }
      catch (InvocationTargetException e) {
          Throwable target = e.getTargetException();
          if (target instanceof Exception) {
              throw (Exception) target;
          }
          throw e;
      }
  }

  protected static CharSequence executableScriptEnvPrefix(String extractRoot) {
      final String gemsDir = new File(extractRoot, "gems").getAbsolutePath();
      final String gemfile = new File(extractRoot, "Gemfile").getAbsolutePath();
      System.out.println("setting GEM_HOME to " + gemsDir);
      System.out.println("... and BUNDLE_GEMFILE to " + gemfile);

      // ideally this would look up the config.override_gem_home setting
      return "ENV['GEM_HOME'] = ENV['GEM_PATH'] = '"+ gemsDir +"' \n" +
      "ENV['BUNDLE_GEMFILE'] ||= '"+ gemfile +"' \n" +
      "require 'uri:classloader:/META-INF/init.rb'";
  }

  protected static String locateExecutableScript(final String executable, final CharSequence envPreScript) {
      return ( envPreScript == null ? "" : envPreScript + " \n" ) +
      "begin\n" + // locate the executable within gemspecs :
      "  require 'rubygems' unless defined?(Gem) \n" +
        "  begin\n" + // add bundled gems to load path :
        "    require 'bundler' \n" +
        "  rescue LoadError\n" + // bundler not used
        "  else\n" +
        "    env = ENV['RAILS_ENV'] || ENV['RACK_ENV'] \n" + // init.rb sets ENV['RAILS_ENV'] ||= ...
        "    env ? Bundler.setup(:default, env) : Bundler.setup(:default) \n" +
        "  end if ENV_JAVA['warbler.bundler.setup'] != 'false' \n" + // java -Dwarbler.bundler.setup=false -jar my.war -S pry
      "  exec = '"+ executable +"' \n" +
      "  spec = Gem::Specification.find { |s| s.executables.include?(exec) } \n" +
      "  spec ? spec.bin_file(exec) : nil \n" +
      // returns the full path to the executable
      "rescue SystemExit => e\n" +
      "  e.status\n" +
      "end";
  }

  protected static String locateExecutable(Object scriptingContainer, String root, final CharSequence envPreScript, String executable) throws Exception {

      final File exec = new File(root, executable);

      System.out.println("locating script " + root + " " + executable);

      if ( exec.exists() ) {
          return exec.getAbsolutePath();
      }

      final String script = locateExecutableScript(executable, envPreScript);
      return (String) invokeMethod(scriptingContainer, "runScriptlet", script);
  }

  protected static Object newScriptingContainer(final URL[] jars, String[] args) throws Exception {
    setSystemProperty("org.jruby.embed.class.path", "");

    URLClassLoader classLoader = new URLClassLoader(jars);

    Class scriptingContainerClass = Class.forName("org.jruby.embed.ScriptingContainer", true, classLoader);
    Object scriptingContainer = scriptingContainerClass.newInstance();

    System.out.println("scripting container class loader urls: " + Arrays.toString(jars));
    invokeMethod(scriptingContainer, "setArgv", (Object) args);
    invokeMethod(scriptingContainer, "setClassLoader", new Class[] { ClassLoader.class }, classLoader);

    return scriptingContainer;
  }

  protected static int launchJRuby(final URL[] jars, File root, String executable, String args[]) throws Exception {
    final Object scriptingContainer = newScriptingContainer(jars, args);

    invokeMethod(scriptingContainer, "setArgv", (Object) args);
    invokeMethod(scriptingContainer, "setCurrentDirectory", root.getAbsolutePath());
    initJRubyScriptingEnv(scriptingContainer);

    final Object provider = invokeMethod(scriptingContainer, "getProvider");
    final Object rubyInstanceConfig = invokeMethod(provider, "getRubyInstanceConfig");

    invokeMethod(rubyInstanceConfig, "setUpdateNativeENVEnabled", new Class[] { Boolean.TYPE }, false);

    final CharSequence execScriptEnvPre = executableScriptEnvPrefix(root.getAbsolutePath());

    final String executablePath = locateExecutable(scriptingContainer, root.getCanonicalPath(), execScriptEnvPre, executable);
    if ( executablePath == null ) {
        throw new IllegalStateException("failed to locate gem executable: '" + executable + "'");
    }
    invokeMethod(scriptingContainer, "setScriptFilename", executablePath);

    invokeMethod(rubyInstanceConfig, "processArguments", (Object) args);

    Object runtime = invokeMethod(scriptingContainer, "getRuntime");

    System.out.println("loading resource: " + executablePath);
    Object executableInput =
        new SequenceInputStream(new ByteArrayInputStream(execScriptEnvPre.toString().getBytes()),
                                (InputStream) invokeMethod(rubyInstanceConfig, "getScriptSource"));

    System.out.println("invoking " + executablePath + " with: " + Arrays.toString(args));

    Object outcome = invokeMethod(runtime, "runFromMain",
            new Class[] { InputStream.class, String.class },
            executableInput, executablePath
    );
    return ( outcome instanceof Number ) ? ( (Number) outcome ).intValue() : 0;
  }

  protected static String launchScript() {
    return
      "begin\n" +
      "  require 'META-INF/init.rb'\n" +
      "  require 'META-INF/main.rb'\n" +
      "  0\n" +
      "rescue SystemExit => e\n" +
      "  e.status\n" +
      "end";
  }

  public static URL[] loadJarUrls() throws Exception {
    List<URL> jars = new ArrayList<URL>();

    File[] files = new File("./WEB-INF/lib/").listFiles();

    for (File f : files) {
      if (f.isFile() && f.getName().endsWith(".jar"))
        jars.add(f.toURI().toURL());
    }
    return jars.toArray(new URL[jars.size()]);
  }

  public static void main(String args[]) throws Exception {

    URL[] jars = loadJarUrls();

    final List<String> argsList = Arrays.asList(args);
    final int sIndex = argsList.indexOf("-S");

    if (sIndex == -1) {
        return;
    }

    String[] arguments = argsList.subList(0, sIndex).toArray(new String[0]);
    String execArg = argsList.get(sIndex + 1);
    String[] executableArgv = argsList.subList(sIndex + 2, argsList.size()).toArray(new String[0]);

    if (execArg.equals("rails")) {
        // The rails executable doesn't play well with ScriptingContainer, so we've packaged the
        // same script that would have been generated by `rake rails:update:bin`
        execArg = "/../META-INF/rails.rb";
    }
    else if (execArg.equals("bundle") && executableArgv.length > 0 && executableArgv[0].equals("exec")) {
        System.out.println("`bundle exec' may drop out of the Warbler environment and into the system environment");
    }

    File root = new File(new File("./WEB-INF/").getCanonicalPath());

    launchJRuby(jars, root, execArg, executableArgv);
  }
}
