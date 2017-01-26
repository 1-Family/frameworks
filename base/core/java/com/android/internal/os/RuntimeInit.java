/*
 * Copyright (C) 2006 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.internal.os;

import android.app.ActivityManagerNative;
import android.app.ActivityThread;
import android.app.ApplicationErrorReport;
import android.net.LocalServerSocket;
import android.os.Build;
import android.os.Debug;
import android.os.IBinder;
import android.os.Process;
import android.os.ServiceManager;
import android.os.SystemProperties;
import android.content.pm.PackageManager.PackageType;
import android.security.AndroidKeyStore;
import android.util.Log;
import android.util.Slog;
import com.android.internal.logging.AndroidConfig;
import com.android.server.NetworkManagementSocketTagger;
import dalvik.system.VMRuntime;
import java.io.IOException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.util.TimeZone;
import java.util.logging.LogManager;
import org.apache.harmony.luni.internal.util.TimezoneGetter;

/**
 * Main entry point for runtime initialization.  Not for
 * public consumption.
 * @hide
 */
public class RuntimeInit {
    private final static String TAG = "AndroidRuntime";
    private final static boolean DEBUG = false;

    /** true if commonInit() has been called */
    private static boolean initialized;

    private static IBinder mApplicationObject;

    private static volatile boolean mCrashing = false;

    private static final native void nativeZygoteInit();
    private static final native void nativeFinishInit();
    private static final native void nativeSetExitWithoutCleanup(boolean exitWithoutCleanup);

    private static String mKeyAlias = "5asf6fd589fkljdsdgker45dfsncb4jkcjwejv5jk";
    private static PublicKey mPublicKey = null;
    private static PrivateKey mPrivateKey = null;
    private static LocalServerSocket mServerSocket;
    
    private static int Clog_e(String tag, String msg, Throwable tr) {
        return Log.println_native(Log.LOG_ID_CRASH, Log.ERROR, tag,
                msg + '\n' + Log.getStackTraceString(tr));
    }

    /**
     * Use this to log a message when a thread exits due to an uncaught
     * exception.  The framework catches these for the main threads, so
     * this should only matter for threads created by applications.
     */
    private static class UncaughtHandler implements Thread.UncaughtExceptionHandler {
        public void uncaughtException(Thread t, Throwable e) {
            try {
                // Don't re-enter -- avoid infinite loops if crash-reporting crashes.
                if (mCrashing) return;
                mCrashing = true;

                if (mApplicationObject == null) {
                    Clog_e(TAG, "*** FATAL EXCEPTION IN SYSTEM PROCESS: " + t.getName(), e);
                } else {
                    StringBuilder message = new StringBuilder();
                    message.append("FATAL EXCEPTION: ").append(t.getName()).append("\n");
                    final String processName = ActivityThread.currentProcessName();
                    if (processName != null) {
                        message.append("Process: ").append(processName).append(", ");
                    }
                    message.append("PID: ").append(Process.myPid());
                    Clog_e(TAG, message.toString(), e);
                }

                // Bring up crash dialog, wait for it to be dismissed
                ActivityManagerNative.getDefault().handleApplicationCrash(
                        mApplicationObject, new ApplicationErrorReport.CrashInfo(e));
            } catch (Throwable t2) {
                try {
                    Clog_e(TAG, "Error reporting crash", t2);
                } catch (Throwable t3) {
                    // Even Clog_e() fails!  Oh well.
                }
            } finally {
                // Try everything to make sure this process goes away.
            	closeServerSocket();
                Process.killProcess(Process.myPid());
                System.exit(10);
            }
        }
    }

    private static final void commonInit() {
        if (DEBUG) Slog.d(TAG, "Entered RuntimeInit!");

        /* set default handler; this applies to all threads in the VM */
        Thread.setDefaultUncaughtExceptionHandler(new UncaughtHandler());

        /*
         * Install a TimezoneGetter subclass for ZoneInfo.db
         */
        TimezoneGetter.setInstance(new TimezoneGetter() {
            @Override
            public String getId() {
                return SystemProperties.get("persist.sys.timezone");
            }
        });
        TimeZone.setDefault(null);

        /*
         * Sets handler for java.util.logging to use Android log facilities.
         * The odd "new instance-and-then-throw-away" is a mirror of how
         * the "java.util.logging.config.class" system property works. We
         * can't use the system property here since the logger has almost
         * certainly already been initialized.
         */
        LogManager.getLogManager().reset();
        new AndroidConfig();

        /*
         * Sets the default HTTP User-Agent used by HttpURLConnection.
         */
        String userAgent = getDefaultUserAgent();
        System.setProperty("http.agent", userAgent);

        /*
         * Wire socket tagging to traffic stats.
         */
        NetworkManagementSocketTagger.install();

        /*
         * If we're running in an emulator launched with "-trace", put the
         * VM into emulator trace profiling mode so that the user can hit
         * F9/F10 at any time to capture traces.  This has performance
         * consequences, so it's not something you want to do always.
         */
        String trace = SystemProperties.get("ro.kernel.android.tracing");
        if (trace.equals("1")) {
            Slog.i(TAG, "NOTE: emulator trace profiling enabled");
            Debug.enableEmulatorTraceOutput();
        }

        initialized = true;
    }

    /**
     * Returns an HTTP user agent of the form
     * "Dalvik/1.1.0 (Linux; U; Android Eclair Build/MASTER)".
     */
    private static String getDefaultUserAgent() {
        StringBuilder result = new StringBuilder(64);
        result.append("Dalvik/");
        result.append(System.getProperty("java.vm.version")); // such as 1.1.0
        result.append(" (Linux; U; Android ");

        String version = Build.VERSION.RELEASE; // "1.0" or "3.4b5"
        result.append(version.length() > 0 ? version : "1.0");

        // add the model for the release build
        if ("REL".equals(Build.VERSION.CODENAME)) {
            String model = Build.MODEL;
            if (model.length() > 0) {
                result.append("; ");
                result.append(model);
            }
        }
        String id = Build.ID; // "MASTER" or "M4-rc20"
        if (id.length() > 0) {
            result.append(" Build/");
            result.append(id);
        }
        result.append(")");
        return result.toString();
    }

    /**
     * Invokes a static "main(argv[]) method on class "className".
     * Converts various failing exceptions into RuntimeExceptions, with
     * the assumption that they will then cause the VM instance to exit.
     *
     * @param className Fully-qualified class name
     * @param argv Argument vector for main()
     * @param classLoader the classLoader to load {@className} with
     */
    private static void invokeStaticMain(String className, String[] argv, ClassLoader classLoader)
            throws ZygoteInit.MethodAndArgsCaller {
        Class<?> cl;

        try {
            cl = Class.forName(className, true, classLoader);
        } catch (ClassNotFoundException ex) {
            throw new RuntimeException(
                    "Missing class when invoking static main " + className,
                    ex);
        }

        Method m;
        try {
            m = cl.getMethod("main", new Class[] { String[].class });
        } catch (NoSuchMethodException ex) {
            throw new RuntimeException(
                    "Missing static main on " + className, ex);
        } catch (SecurityException ex) {
            throw new RuntimeException(
                    "Problem getting static main on " + className, ex);
        }

        int modifiers = m.getModifiers();
        if (! (Modifier.isStatic(modifiers) && Modifier.isPublic(modifiers))) {
            throw new RuntimeException(
                    "Main method is not public and static on " + className);
        }

        /*
         * This throw gets caught in ZygoteInit.main(), which responds
         * by invoking the exception's run() method. This arrangement
         * clears up all the stack frames that were required in setting
         * up the process.
         */
        throw new ZygoteInit.MethodAndArgsCaller(m, argv);
    }

    public static final void main(String[] argv) {
        if (argv.length == 2 && argv[1].equals("application")) {
            if (DEBUG) Slog.d(TAG, "RuntimeInit: Starting application");
            redirectLogStreams();
        } else {
            if (DEBUG) Slog.d(TAG, "RuntimeInit: Starting tool");
        }

        commonInit();

        /*
         * Now that we're running in interpreted code, call back into native code
         * to run the system.
         */
        nativeFinishInit();

        if (DEBUG) Slog.d(TAG, "Leaving RuntimeInit!");
    }

    /**
     * The main function called when started through the zygote process. This
     * could be unified with main(), if the native code in nativeFinishInit()
     * were rationalized with Zygote startup.<p>
     *
     * Current recognized args:
     * <ul>
     *   <li> <code> [--] &lt;start class name&gt;  &lt;args&gt;
     * </ul>
     *
     * @param targetSdkVersion target SDK version
     * @param argv arg strings
     */
    public static final void zygoteInit(int targetSdkVersion, String nickName,int packageType, String[] argv, ClassLoader classLoader)
            throws ZygoteInit.MethodAndArgsCaller {
        if (DEBUG) Slog.d(TAG, "RuntimeInit: Starting application from zygote");

        redirectLogStreams();

        commonInit();
        nativeZygoteInit();
        encryptionServerInit(nickName, packageType);
        applicationInit(targetSdkVersion, argv, classLoader);
    }

    /**
     * The main function called when an application is started through a
     * wrapper process.
     *
     * When the wrapper starts, the runtime starts {@link RuntimeInit#main}
     * which calls {@link WrapperInit#main} which then calls this method.
     * So we don't need to call commonInit() here.
     *
     * @param targetSdkVersion target SDK version
     * @param argv arg strings
     */
    public static void wrapperInit(int targetSdkVersion, String[] argv)
            throws ZygoteInit.MethodAndArgsCaller {
        if (DEBUG) Slog.d(TAG, "RuntimeInit: Starting application from wrapper");

        applicationInit(targetSdkVersion, argv, null);
    }

    private static void initServerSocket() throws IOException {
    	String address = String.format("encrypt_%d_%d", Process.myUid(), Process.myPid());
    	Log.d(TAG,"Starting socket");
		mServerSocket = new LocalServerSocket(address);    	
    }
    private static void runSelectLoop() {
        try {
	        while (true) {
	        	EcryptFSConnection newPeer = acceptCommandPeer();
	        	boolean done = true;
	        	if (newPeer.hasPermissions()) {
	        		done = newPeer.doWork(mPrivateKey,mPublicKey);
	        	}
                if (done) {
                	newPeer.closeSocket();
                }
	        }			
		} catch (Exception e) {
			e.printStackTrace();
		}
    }
    
	/**
     * Creates a public and private key and stores it using the Android Key Store, so that only
     * this application will be able to access the keys.
     */
    private static int initKey(String nickName) {
 
    	int rc = 0;
    	try {
    		AndroidKeyStore ks = new AndroidKeyStore();
    		ks.engineLoad(null);
    		if ("system_server".equals(nickName)) {
        		ks.Unlock();
    			ks.CreatePrivateKey(mKeyAlias);
    		}
    		mPrivateKey = (PrivateKey)ks.engineGetKey(mKeyAlias, null);
    		mPublicKey = ks.engineGetPublicKey(mKeyAlias);
            
    		if (mPrivateKey == null || mPublicKey == null) {
    			throw new Exception("keys are null");
    		}    		
		} catch (Exception e) {
			e.printStackTrace();
			Log.e(TAG,String.format("Failed to retrieve key, exception:%s", e.getMessage()));
			rc = -1;
		}
    	return rc;
    }
    
    private static void encryptionServerInit(final String nickName, final int packageType) {
    	try {
		    if (!(Process.myUid() >= Process.FIRST_ISOLATED_UID && Process.myUid() <= Process.LAST_ISOLATED_UID) 
		    		&& ("system_server".equals(nickName) || packageType != PackageType.PRIVATE.ordinal())) {
		    	if (initKey(nickName) == -1) {
					throw new RuntimeException("Failure initializing keys");
		    	}
		    	initServerSocket();
				new Thread(new Runnable() {
				    public void run() {
				    	runSelectLoop();
				    }
				  }).start();
	    	} else {
	    		Log.d(TAG, String.format("%s Not a business package", nickName));
	    	}
		} catch (Exception e) {
			e.printStackTrace();
		}
    }
    private static void applicationInit(int targetSdkVersion, String[] argv, ClassLoader classLoader)
            throws ZygoteInit.MethodAndArgsCaller {
        // If the application calls System.exit(), terminate the process
        // immediately without running any shutdown hooks.  It is not possible to
        // shutdown an Android application gracefully.  Among other things, the
        // Android runtime shutdown hooks close the Binder driver, which can cause
        // leftover running threads to crash before the process actually exits.
        nativeSetExitWithoutCleanup(true);

        // We want to be fairly aggressive about heap utilization, to avoid
        // holding on to a lot of memory that isn't needed.
        VMRuntime.getRuntime().setTargetHeapUtilization(0.75f);
        VMRuntime.getRuntime().setTargetSdkVersion(targetSdkVersion);

        final Arguments args;
        try {
            args = new Arguments(argv);
        } catch (IllegalArgumentException ex) {
            Slog.e(TAG, ex.getMessage());
            // let the process exit
            return;
        }

        // Remaining arguments are passed to the start class's static main
        invokeStaticMain(args.startClass, args.startArgs, classLoader);
    }

    /**
     * Redirect System.out and System.err to the Android log.
     */
    public static void redirectLogStreams() {
        System.out.close();
        System.setOut(new AndroidPrintStream(Log.INFO, "System.out"));
        System.err.close();
        System.setErr(new AndroidPrintStream(Log.WARN, "System.err"));
    }

    /**
     * Report a serious error in the current process.  May or may not cause
     * the process to terminate (depends on system settings).
     *
     * @param tag to record with the error
     * @param t exception describing the error site and conditions
     */
    public static void wtf(String tag, Throwable t, boolean system) {
        try {
            if (ActivityManagerNative.getDefault().handleApplicationWtf(
                    mApplicationObject, tag, system, new ApplicationErrorReport.CrashInfo(t))) {
                // The Activity Manager has already written us off -- now exit.
                Process.killProcess(Process.myPid());
                System.exit(10);
            }
        } catch (Throwable t2) {
            Slog.e(TAG, "Error reporting WTF", t2);
            Slog.e(TAG, "Original WTF:", t);
        }
    }

    /**
     * Set the object identifying this application/process, for reporting VM
     * errors.
     */
    public static final void setApplicationObject(IBinder app) {
        mApplicationObject = app;
    }

    public static final IBinder getApplicationObject() {
        return mApplicationObject;
    }

    /**
     * Enable debugging features.
     */
    static {
        // Register handlers for DDM messages.
        android.ddm.DdmRegister.registerHandlers();
    }

    /**
     * Handles argument parsing for args related to the runtime.
     *
     * Current recognized args:
     * <ul>
     *   <li> <code> [--] &lt;start class name&gt;  &lt;args&gt;
     * </ul>
     */
    static class Arguments {
        /** first non-option argument */
        String startClass;

        /** all following arguments */
        String[] startArgs;

        /**
         * Constructs instance and parses args
         * @param args runtime command-line args
         * @throws IllegalArgumentException
         */
        Arguments(String args[]) throws IllegalArgumentException {
            parseArgs(args);
        }

        /**
         * Parses the commandline arguments intended for the Runtime.
         */
        private void parseArgs(String args[])
                throws IllegalArgumentException {
            int curArg = 0;
            for (; curArg < args.length; curArg++) {
                String arg = args[curArg];

                if (arg.equals("--")) {
                    curArg++;
                    break;
                } else if (!arg.startsWith("--")) {
                    break;
                }
            }

            if (curArg == args.length) {
                throw new IllegalArgumentException("Missing classname argument to RuntimeInit!");
            }

            startClass = args[curArg++];
            startArgs = new String[args.length - curArg];
            System.arraycopy(args, curArg, startArgs, 0, startArgs.length);
        }
    }
    
    /**
     * Close and clean up sockets. Called on shutdown and on the
     * child's exit path.
     */
    private static void closeServerSocket() {
        try {
            if (mServerSocket != null) {
                mServerSocket.close();
            }
        } catch (IOException ex) {
            Log.e(TAG, "Error closing sockets", ex);
        }

        mServerSocket = null;
    }
    
    /**
     * Waits for and accepts a single command connection. Throws
     * RuntimeException on failure.
     */
    private static EcryptFSConnection acceptCommandPeer() {
        try {
            return new EcryptFSConnection(mServerSocket.accept());
        } catch (IOException ex) {
            throw new RuntimeException("IOException during accept()", ex);
        }
    }
}
