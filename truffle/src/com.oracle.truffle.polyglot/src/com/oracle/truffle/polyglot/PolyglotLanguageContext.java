/*
 * Copyright (c) 2017, 2023, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * The Universal Permissive License (UPL), Version 1.0
 *
 * Subject to the condition set forth below, permission is hereby granted to any
 * person obtaining a copy of this software, associated documentation and/or
 * data (collectively the "Software"), free of charge and under any and all
 * copyright rights in the Software, and any and all patent rights owned or
 * freely licensable by each licensor hereunder covering either (i) the
 * unmodified Software as contributed to or provided by such licensor, or (ii)
 * the Larger Works (as defined below), to deal in both
 *
 * (a) the Software, and
 *
 * (b) any piece of software and/or hardware listed in the lrgrwrks.txt file if
 * one is included with the Software each a "Larger Work" to which the Software
 * is contributed by such licensors),
 *
 * without restriction, including without limitation the rights to copy, create
 * derivative works of, display, perform, and distribute the Software and make,
 * use, sell, offer for sale, import, export, have made, and have sold the
 * Software and the Larger Work(s), and to sublicense the foregoing rights on
 * either these or other terms.
 *
 * This license is subject to the following condition:
 *
 * The above copyright notice and either this complete permission notice or at a
 * minimum a reference to the UPL must be included in all copies or substantial
 * portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package com.oracle.truffle.polyglot;

import static com.oracle.truffle.api.CompilerDirectives.shouldNotReachHere;
import static com.oracle.truffle.polyglot.EngineAccessor.LANGUAGE;

import java.io.PrintStream;
import java.io.Serial;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.function.Supplier;
import java.util.logging.Level;

import org.graalvm.options.OptionDescriptor;
import org.graalvm.polyglot.impl.AbstractPolyglotImpl;
import org.graalvm.polyglot.impl.AbstractPolyglotImpl.APIAccess;

import com.oracle.truffle.api.CallTarget;
import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.ThreadLocalAction;
import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.TruffleLanguage.Env;
import com.oracle.truffle.api.TruffleLogger;
import com.oracle.truffle.api.TruffleSafepoint;
import com.oracle.truffle.api.dsl.Cached;
import com.oracle.truffle.api.dsl.Cached.Shared;
import com.oracle.truffle.api.dsl.GenerateCached;
import com.oracle.truffle.api.dsl.GenerateInline;
import com.oracle.truffle.api.dsl.GenerateUncached;
import com.oracle.truffle.api.dsl.NeverDefault;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.exception.AbstractTruffleException;
import com.oracle.truffle.api.frame.Frame;
import com.oracle.truffle.api.interop.InteropLibrary;
import com.oracle.truffle.api.interop.NodeLibrary;
import com.oracle.truffle.api.interop.UnsupportedMessageException;
import com.oracle.truffle.api.nodes.ExplodeLoop;
import com.oracle.truffle.api.nodes.LanguageInfo;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.nodes.RootNode;
import com.oracle.truffle.api.profiles.InlinedBranchProfile;
import com.oracle.truffle.api.source.Source;

/** The data corresponding to a specific context of a {@link TruffleLanguage}. */
final class PolyglotLanguageContext implements PolyglotImpl.VMObject {

    private static final TruffleLogger LOG = TruffleLogger.getLogger(PolyglotEngineImpl.OPTION_GROUP_ENGINE, PolyglotLanguageContext.class);
    private static final PolyglotThreadLocalActions.HandshakeConfig INITIALIZE_THREAD_HANDSHAKE_CONFIG = new PolyglotThreadLocalActions.HandshakeConfig(true, false, false, false);

    /*
     * Lazily created when a language context is created.
     */
    final class Lazy {

        final Set<Thread> ownedAlivePolyglotThreads;
        final Object polyglotGuestBindings;
        final Thread.UncaughtExceptionHandler uncaughtExceptionHandler;
        @CompilationFinal PolyglotLanguageInstance languageInstance;
        @CompilationFinal Map<String, LanguageInfo> accessibleInternalLanguages;
        @CompilationFinal Map<String, LanguageInfo> accessiblePublicLanguages;
        final Object internalFileSystemContext;
        final Object publicFileSystemContext;

        private boolean multipleThreadsInitialized;

        Lazy(PolyglotLanguageInstance languageInstance, PolyglotContextConfig config) {
            this.languageInstance = languageInstance;
            this.ownedAlivePolyglotThreads = new HashSet<>();
            this.polyglotGuestBindings = new PolyglotBindings(PolyglotLanguageContext.this);
            this.uncaughtExceptionHandler = new PolyglotUncaughtExceptionHandler();
            this.computeAccessPermissions(config);
            // file systems are patched after preinitialization internally using a delegate field
            this.publicFileSystemContext = EngineAccessor.LANGUAGE.createFileSystemContext(PolyglotLanguageContext.this, config.fileSystemConfig.fileSystem);
            this.internalFileSystemContext = EngineAccessor.LANGUAGE.createFileSystemContext(PolyglotLanguageContext.this, config.fileSystemConfig.internalFileSystem);
        }

        void computeAccessPermissions(PolyglotContextConfig config) {
            this.accessibleInternalLanguages = computeAccessibleLanguages(config, true);
            this.accessiblePublicLanguages = computeAccessibleLanguages(config, false);
        }

        private Map<String, LanguageInfo> computeAccessibleLanguages(PolyglotContextConfig config, boolean internal) {
            PolyglotLanguage thisLanguage = languageInstance.language;
            if (thisLanguage.isHost()) {
                return languageInstance.getEngine().idToInternalLanguageInfo;
            }
            boolean embedderAllAccess = config.allowedPublicLanguages.isEmpty();
            PolyglotEngineImpl engine = languageInstance.getEngine();
            Set<String> configuredAccess = null;
            Set<String> configured = engine.getAPIAccess().getEvalAccess(config.polyglotAccess, thisLanguage.getId());
            if (configured != null) {
                configuredAccess = new HashSet<>(configured);
            }

            Set<String> resolveLanguages;
            if (embedderAllAccess) {
                if (configuredAccess == null) {
                    if (internal) {
                        return engine.idToInternalLanguageInfo;
                    } else {
                        resolveLanguages = new HashSet<>();
                        resolveLanguages.addAll(engine.idToInternalLanguageInfo.keySet());
                    }
                } else {
                    resolveLanguages = new HashSet<>(configuredAccess);
                    resolveLanguages.add(thisLanguage.getId());
                }
            } else {
                if (configuredAccess == null) {
                    // all access configuration
                    configuredAccess = config.allowedPublicLanguages;
                }
                resolveLanguages = new HashSet<>(configuredAccess);
                resolveLanguages.add(thisLanguage.getId());
            }

            Map<String, LanguageInfo> resolvedLanguages = new LinkedHashMap<>();
            for (String id : resolveLanguages) {
                PolyglotLanguage resolvedLanguage = engine.idToLanguage.get(id);
                if (resolvedLanguage != null) { // resolved languages might not be on the
                                                // class-path.
                    if (!internal && resolvedLanguage.cache.isInternal()) {
                        // filter internal
                        continue;
                    }
                    resolvedLanguages.put(id, resolvedLanguage.info);
                }
            }
            if (internal) {
                addDependentLanguages(engine, resolvedLanguages, thisLanguage);
            }

            // all internal languages are accessible by default
            if (internal) {
                for (Entry<String, PolyglotLanguage> entry : languageInstance.getEngine().idToLanguage.entrySet()) {
                    if (entry.getValue().cache.isInternal()) {
                        resolvedLanguages.put(entry.getKey(), entry.getValue().info);
                    }
                }
                assert assertPermissionsConsistent(resolvedLanguages, languageInstance.language, config);
            }
            return resolvedLanguages;
        }

        private boolean assertPermissionsConsistent(Map<String, LanguageInfo> resolvedLanguages, PolyglotLanguage thisLanguage, PolyglotContextConfig config) {
            for (Entry<String, PolyglotLanguage> entry : languageInstance.getEngine().idToLanguage.entrySet()) {
                boolean permitted = config.isAccessPermitted(thisLanguage, entry.getValue());
                assert permitted == resolvedLanguages.containsKey(entry.getKey()) : "inconsistent access permissions";
            }
            return true;
        }

        private void addDependentLanguages(PolyglotEngineImpl engine, Map<String, LanguageInfo> resolvedLanguages, PolyglotLanguage currentLanguage) {
            for (String dependentLanguage : currentLanguage.cache.getDependentLanguages()) {
                PolyglotLanguage dependent = engine.idToLanguage.get(dependentLanguage);
                if (dependent == null) { // dependent languages might not exist.
                    continue;
                }
                if (resolvedLanguages.containsKey(dependentLanguage)) {
                    continue; // cycle or duplicate detection
                }
                resolvedLanguages.put(dependentLanguage, dependent.info);
                addDependentLanguages(engine, resolvedLanguages, dependent);
            }
        }
    }

    final PolyglotContextImpl context;
    final PolyglotLanguage language;
    final boolean eventsEnabled;

    private Thread creatingThread;
    private volatile boolean created;
    private volatile boolean initialized;
    private boolean initializationFailed;
    private volatile Thread initializingThread;
    volatile boolean finalized;
    volatile TruffleLanguage.ExitMode exited;
    @CompilationFinal private volatile Object hostBindings;
    @CompilationFinal private volatile Lazy lazy;
    @CompilationFinal volatile Env env; // effectively final
    @CompilationFinal private volatile List<Object> languageServices = Collections.emptyList();

    PolyglotLanguageContext(PolyglotContextImpl context, PolyglotLanguage language) {
        this.context = context;
        this.language = language;
        this.eventsEnabled = !language.isHost();
    }

    boolean isPolyglotBindingsAccessAllowed() {
        if (context.config.polyglotAccess == language.getAPIAccess().getPolyglotAccessAll()) {
            return true;
        }

        Set<String> accessibleLanguages = getAPIAccess().getBindingsAccess(context.config.polyglotAccess);
        if (accessibleLanguages == null) {
            return true;
        }
        return accessibleLanguages.contains(language.getId());
    }

    boolean isPolyglotEvalAllowed(LanguageInfo info) {
        Set<String> languageAccess = getAPIAccess().getEvalAccess(context.config.polyglotAccess, language.getId());
        if (languageAccess != null && languageAccess.isEmpty()) {
            return false;
        }
        if (info == null) {
            return true;
        } else {
            return getAccessibleLanguages(false).containsKey(info.getId());
        }
    }

    Thread.UncaughtExceptionHandler getPolyglotExceptionHandler() {
        assert env != null;
        return lazy.uncaughtExceptionHandler;
    }

    Map<String, LanguageInfo> getAccessibleLanguages(boolean allowInternalAndDependent) {
        Lazy l = lazy;
        if (l != null) {
            if (allowInternalAndDependent) {
                return lazy.accessibleInternalLanguages;
            } else {
                return lazy.accessiblePublicLanguages;
            }
        } else {
            return null;
        }
    }

    PolyglotLanguageInstance getLanguageInstanceOrNull() {
        Lazy l = this.lazy;
        if (l == null) {
            return null;
        }
        return l.languageInstance;
    }

    PolyglotLanguageInstance getLanguageInstance() {
        assert env != null;
        return lazy.languageInstance;
    }

    private void checkThreadAccess(Env localEnv) throws PolyglotThreadAccessException {
        assert Thread.holdsLock(context);
        boolean singleThreaded = context.isSingleThreaded();
        Thread firstFailingThread = null;
        for (PolyglotThreadInfo threadInfo : context.getSeenThreads().values()) {
            if (!LANGUAGE.isThreadAccessAllowed(localEnv, threadInfo.getThread(), singleThreaded)) {
                firstFailingThread = threadInfo.getThread();
                break;
            }
        }
        if (firstFailingThread != null) {
            throw PolyglotContextImpl.throwDeniedThreadAccess(firstFailingThread, singleThreaded, Arrays.asList(language));
        }
    }

    Object getContextImpl() {
        Env localEnv = env;
        if (localEnv != null) {
            return LANGUAGE.getContext(localEnv);
        } else {
            CompilerDirectives.transferToInterpreterAndInvalidate();
            return null;
        }
    }

    Object getPublicFileSystemContext() {
        Lazy l = lazy;
        if (l != null) {
            return l.publicFileSystemContext;
        } else {
            return null;
        }
    }

    Object getInternalFileSystemContext() {
        Lazy l = lazy;
        if (l != null) {
            return l.internalFileSystemContext;
        } else {
            return null;
        }
    }

    Object getHostBindings() {
        assert initialized;
        if (this.hostBindings == null) {
            synchronized (this) {
                if (this.hostBindings == null) {
                    Object prev = language.engine.enterIfNeeded(context, true);
                    try {
                        Object scope = LANGUAGE.getScope(env);
                        if (scope == null) {
                            scope = new DefaultTopScope();
                        }
                        this.hostBindings = scope;
                    } finally {
                        language.engine.leaveIfNeeded(prev, context);
                    }
                }
            }
        }
        return asValue(hostBindings);
    }

    Object getPolyglotGuestBindings() {
        assert isInitialized();
        return this.lazy.polyglotGuestBindings;
    }

    boolean isInitialized() {
        return initialized;
    }

    CallTarget parseCached(PolyglotLanguage accessingLanguage, Source source, String[] argumentNames) throws AssertionError {
        ensureInitialized(accessingLanguage);
        PolyglotSourceCache cache = context.layer.getSourceCache();
        assert cache != null;
        return cache.parseCached(this, source, argumentNames);
    }

    Env requireEnv() {
        Env localEnv = this.env;
        if (localEnv == null) {
            throw shouldNotReachHere("No language context is active on this thread.");
        }
        return localEnv;
    }

    boolean finalizeContext(boolean mustSucceed, boolean notifyInstruments) {
        if (waitForInitializationAndThen(!mustSucceed, () -> {
            assert Thread.holdsLock(context);
            if (!initialized || finalized) {
                return false;
            } else {
                finalized = true;
                return true;
            }
        })) {
            /*
             * The finalize notification operation is not needed to be under the context lock. We
             * know the language context is already initialized and parallel execution with the exit
             * operation is prevented by other means. Parallel execution of two or more finalize
             * notification operations is prevented by setting finalized under the context lock.
             */
            try {
                LANGUAGE.finalizeContext(env);
            } catch (Throwable t) {
                if (context.shouldThrowException(mustSucceed, t, "finalizing")) {
                    throw t;
                }
            }
            if (eventsEnabled && notifyInstruments) {
                EngineAccessor.INSTRUMENT.notifyLanguageContextFinalized(context.engine, context.getCreatorTruffleContext(), language.info);
            }
            return true;
        }
        return false;
    }

    boolean exitContext(TruffleLanguage.ExitMode exitMode, int exitCode) {
        if (waitForInitializationAndThen(true, () -> {
            assert Thread.holdsLock(context);
            if (!initialized || (exited != null && exitMode.ordinal() <= exited.ordinal())) {
                return false;
            } else {
                exited = exitMode;
                return true;
            }
        })) {
            /*
             * The exit notification operation is not needed to be under the context lock. We know
             * the language context is already initialized and parallel execution with the finalize
             * operation is prevented by other means. Moreover, we need to make it possible for the
             * natural and the hard exit notifications to be executed in parallel in case the hard
             * exit is triggered while the natural exit notifications are already in progress.
             * Parallel execution of two or more exit notification operations of the same type is
             * prevented by setting exited under the context lock.
             */
            try {
                LANGUAGE.exitContext(env, exitMode, exitCode);
            } catch (Throwable t) {
                if (exitMode == TruffleLanguage.ExitMode.NATURAL || (!(t instanceof AbstractTruffleException) && !(t instanceof PolyglotContextImpl.ExitException))) {
                    throw t;
                } else {
                    if (t instanceof AbstractTruffleException && !context.state.isCancelling()) {
                        context.engine.getEngineLogger().log(Level.WARNING, "TruffleException thrown during exit notification! Languages are supposed to handle this kind of exceptions.", t);
                    } else {
                        context.engine.getEngineLogger().log(Level.FINE, "Exception thrown during exit notification!", t);
                    }
                }
            }
            return true;
        }
        return false;
    }

    boolean dispose() {
        try {
            Env localEnv;
            List<Thread> threadsToDispose = null;
            synchronized (context) {
                localEnv = this.env;
                if (localEnv != null) {
                    if (!lazy.ownedAlivePolyglotThreads.isEmpty()) {
                        // this should show up as internal error so it does not use
                        // PolyglotEngineException
                        throw new IllegalStateException("The language did not complete all polyglot threads but should have: " + lazy.ownedAlivePolyglotThreads);
                    }
                    for (PolyglotThreadInfo threadInfo : context.getSeenThreads().values()) {
                        assert threadInfo != PolyglotThreadInfo.NULL;
                        final Thread thread = threadInfo.getThread();
                        if (thread == null) {
                            continue;
                        }
                        assert !threadInfo.isPolyglotThread() : "Polyglot threads must no longer be active in TruffleLanguage.finalizeContext, but polyglot thread " + thread.getName() +
                                        " is still active.";
                        if (!threadInfo.isCurrent() && threadInfo.isActive() && !context.state.isInvalidOrClosed()) {
                            /*
                             * No other thread than the current thread should be active here.
                             * However, we do this check only for non-invalid contexts for the
                             * following reasons. enteredCount for a thread can be incremented on
                             * the fast-path even though the thread is not allowed to enter in the
                             * end because the context is invalid and so the enter falls back to the
                             * slow path which checks the invalid flag. threadInfo.isActive()
                             * returns true in this case and we cannot tell whether it is because
                             * the thread is before the fallback to the slow path or it is already
                             * fully entered (which would be an error) without adding further checks
                             * to the fast path, and so we don't perform the check for invalid
                             * contexts. Non-invalid context can have the same problem with the
                             * enteredCount of one of its threads, but closing non-invalid context
                             * in that state is an user error.
                             */
                            throw PolyglotEngineException.illegalState("Another main thread was started while closing a polyglot context!");
                        }
                        if (threadInfo.isLanguageContextInitialized(language)) {
                            if (threadsToDispose == null) {
                                threadsToDispose = new ArrayList<>();
                            }
                            threadsToDispose.add(thread);
                        }
                    }
                }
            }
            if (threadsToDispose != null) {
                for (Thread thread : threadsToDispose) {
                    LANGUAGE.disposeThread(localEnv, thread);
                }
            }
            // Call TruffleLanguage#disposeContext without holding the context lock.
            if (localEnv != null) {
                LANGUAGE.dispose(localEnv);
                return true;
            } else {
                return false;
            }
        } catch (Throwable t) {
            if (!PolyglotContextImpl.isInternalError(t)) {
                throw new IllegalStateException("Guest language code was run during language disposal!", t);
            }
            throw t;
        }
    }

    void notifyDisposed(boolean notifyInstruments) {
        if (eventsEnabled && notifyInstruments) {
            EngineAccessor.INSTRUMENT.notifyLanguageContextDisposed(context.engine, context.getCreatorTruffleContext(), language.info);
        }
    }

    Object[] enterThread(PolyglotThreadTask polyglotThreadTask) {
        assert isInitialized();
        Thread currentThread = Thread.currentThread();
        synchronized (context) {
            /*
             * Don't add the thread to alive threads if the context is invalid. If the context
             * becomes invalid just after the check, it is fine, because the thread is already
             * started, and so the context closing mechanism has to wait for the thread to finish -
             * either as a part of the context cancelling mechanism or in
             * TruffleLanguage#finalizeContext if the context is closed normally.
             */
            context.checkClosedOrDisposing(false);
            if (context.finalizingEmbedderThreads) {
                throw PolyglotEngineException.closedException("The Context is already closed.");
            }
            lazy.ownedAlivePolyglotThreads.add(currentThread);
        }
        try {
            if (polyglotThreadTask.beforeEnter != null) {
                context.enterDisallowedForPolyglotThread.add(currentThread);
                try {
                    polyglotThreadTask.beforeEnter.run();
                } finally {
                    context.enterDisallowedForPolyglotThread.remove(currentThread);
                }
            }
            return context.enterThreadChanged(false, true, false, polyglotThreadTask, false);
        } catch (Throwable t) {
            synchronized (context) {
                lazy.ownedAlivePolyglotThreads.remove(currentThread);
                context.notifyAll();
            }
            throw t;
        }
    }

    void leaveAndDisposePolyglotThread(Object[] prev, PolyglotThreadTask polyglotThreadTask) {
        assert isInitialized();
        Thread currentThread = Thread.currentThread();
        try {
            context.leaveThreadChanged(prev, true, true);
            if (polyglotThreadTask.afterLeave != null) {
                context.enterDisallowedForPolyglotThread.add(currentThread);
                try {
                    polyglotThreadTask.afterLeave.run();
                } finally {
                    context.enterDisallowedForPolyglotThread.remove(currentThread);
                }
            }
        } finally {
            synchronized (context) {
                boolean removed = lazy.ownedAlivePolyglotThreads.remove(Thread.currentThread());
                context.notifyAll();
                assert removed : "thread was not removed from language context";
            }
        }
    }

    boolean isCreated() {
        return created;
    }

    OptionValuesImpl parseSourceOptions(Source source, String componentOnly) {
        Map<String, String> rawOptions = EngineAccessor.SOURCE.getSourceOptions(source);
        if (rawOptions.isEmpty()) {
            // fast-path: no options
            return language.getEmptySourceOptionsInternal();
        }
        PolyglotContextConfig config = context.config;
        Map<String, OptionValuesImpl> options = PolyglotSourceCache.parseSourceOptions(getEngine(),
                        rawOptions, componentOnly,
                        config.sandboxPolicy,
                        config.allowExperimentalOptions);
        OptionValuesImpl languageOptions = options.get(componentOnly != null ? componentOnly : source.getLanguage());
        if (languageOptions == null) {
            return language.getEmptySourceOptionsInternal();
        }
        List<OptionDescriptor> deprecated = null;
        for (OptionValuesImpl resolvedOptions : options.values()) {
            Collection<OptionDescriptor> descriptors = resolvedOptions.getUsedDeprecatedDescriptors();
            if (!descriptors.isEmpty()) {
                if (deprecated == null) {
                    deprecated = new ArrayList<>();
                }
                deprecated.addAll(descriptors);
            }
        }

        if (deprecated != null) {
            context.engine.printDeprecatedOptionsWarning(deprecated);
        }

        return languageOptions;
    }

    void ensureCreated(PolyglotLanguage accessingLanguage) {
        if (creatingThread == Thread.currentThread()) {
            throw PolyglotEngineException.illegalState(String.format("Cyclic access to language context for language %s. " +
                            "The context is currently being created.", language.getId()));
        }

        if (!created) {
            if (context.finalizingEmbedderThreads) {
                throw PolyglotEngineException.illegalState(String.format(
                                "Creation of context for language %s is no longer allowed. Language contexts are finalized when embedder threads are being finalized.", language.getId()));
            }

            language.validateSandbox(context.config.sandboxPolicy);
            checkAccess(accessingLanguage);

            Map<String, Object> creatorConfig = context.creator == language ? context.config.creatorArguments : Collections.emptyMap();
            PolyglotContextConfig contextConfig = context.config;

            PolyglotLanguageInstance languageInstance;
            PolyglotSharingLayer layer = context.layer;
            synchronized (context.engine.lock) {
                if (language.isHost()) {
                    if (layer.isClaimed() && layer.hostLanguage == null) {
                        // Patching layer created by context pre-initialization.
                        languageInstance = layer.patchHostLanguage(language);
                    } else {
                        languageInstance = layer.allocateHostLanguage(language);
                    }
                } else {
                    context.claimSharingLayer(language);
                    languageInstance = layer.allocateInstance(context, language);
                }
            }

            PolyglotThreadAccessException threadAccessException = null;
            loop: for (;;) {
                if (threadAccessException != null) {
                    threadAccessException.rethrow(context);
                }
                synchronized (context) {
                    if (!created) {
                        if (eventsEnabled) {
                            EngineAccessor.INSTRUMENT.notifyLanguageContextCreate(context.engine, context.getCreatorTruffleContext(), language.info);
                        }
                        boolean wasCreated = false;
                        try {
                            Env localEnv = LANGUAGE.createEnv(this, languageInstance.spi, contextConfig.out,
                                            contextConfig.err,
                                            contextConfig.in,
                                            creatorConfig,
                                            contextConfig.getLanguageOptionValues(language).copy(),
                                            contextConfig.getApplicationArguments(language));
                            Lazy localLazy = new Lazy(languageInstance, contextConfig);

                            if (layer.isSingleContext()) {
                                languageInstance.singleLanguageContext.update(this);
                            } else {
                                languageInstance.singleLanguageContext.invalidate();
                            }

                            try {
                                checkThreadAccess(localEnv);
                            } catch (PolyglotThreadAccessException ex) {
                                threadAccessException = ex;
                                continue loop;
                            }

                            // no more errors after this line
                            creatingThread = Thread.currentThread();
                            env = localEnv;
                            lazy = localLazy;
                            assert EngineAccessor.LANGUAGE.getLanguage(env) != null;

                            try {
                                List<Object> languageServicesCollector = new ArrayList<>();
                                Object contextImpl = LANGUAGE.createEnvContext(localEnv, languageServicesCollector);
                                language.initializeContextClass(contextImpl);
                                String errorMessage = verifyServices(language.info, languageServicesCollector, language.cache.getServices());
                                if (errorMessage != null) {
                                    throw PolyglotEngineException.illegalState(errorMessage);
                                }
                                PolyglotFastThreadLocals.notifyLanguageCreated(this);
                                this.languageServices = languageServicesCollector;
                                if (language.isHost()) {
                                    context.initializeHostContext(this, context.config);
                                }
                                wasCreated = true;
                                if (eventsEnabled) {
                                    EngineAccessor.INSTRUMENT.notifyLanguageContextCreated(context.engine, context.getCreatorTruffleContext(), language.info);
                                }
                                context.invokeContextLocalsFactory(context.contextLocals, languageInstance.contextLocalLocations);
                                context.invokeContextThreadLocalFactory(languageInstance.contextThreadLocalLocations);

                                languageInstance = null; // commit language use
                            } catch (Throwable e) {
                                env = null;
                                lazy = null;
                                throw e;
                            } finally {
                                creatingThread = null;
                            }
                            created = true;
                        } finally {
                            if (!wasCreated && eventsEnabled) {
                                EngineAccessor.INSTRUMENT.notifyLanguageContextCreateFailed(context.engine, context.getCreatorTruffleContext(), language.info);
                            }
                        }
                    }
                }
                break loop;
            }
        }
    }

    void close() {
        assert Thread.holdsLock(context);
        created = false;
        lazy = null;
        env = null;
    }

    private static String verifyServices(LanguageInfo info, List<Object> registeredServices, Collection<String> expectedServices) {
        for (String expectedService : expectedServices) {
            boolean found = false;
            for (Object registeredService : registeredServices) {
                if (isSubType(registeredService.getClass(), expectedService)) {
                    found = true;
                    break;
                }
            }
            if (!found) {
                return String.format("Language %s declares service %s but doesn't register it", info.getName(), expectedService);
            }
        }
        return null;
    }

    private static boolean isSubType(Class<?> clazz, String serviceClass) {
        if (clazz == null) {
            return false;
        }
        if (serviceClass.equals(clazz.getName()) || serviceClass.equals(clazz.getCanonicalName())) {
            return true;
        }
        if (isSubType(clazz.getSuperclass(), serviceClass)) {
            return true;
        }
        for (Class<?> implementedInterface : clazz.getInterfaces()) {
            if (isSubType(implementedInterface, serviceClass)) {
                return true;
            }
        }
        return false;
    }

    @SuppressWarnings("SynchronizationOnLocalVariableOrMethodParameter")
    <T> T waitForInitializationAndThen(boolean pollSafepoint, Supplier<T> action) {
        TruffleSafepoint.InterruptibleFunction<PolyglotContextImpl, T> waitAndExecuteAction = polyglotContext -> {
            boolean interrupted = false;
            synchronized (polyglotContext) {
                while (initializingThread != null && initializingThread != Thread.currentThread()) {
                    try {
                        polyglotContext.wait();
                    } catch (InterruptedException ie) {
                        if (pollSafepoint) {
                            throw ie;
                        } else {
                            interrupted = true;
                        }
                    }
                }
                if (interrupted) {
                    Thread.currentThread().interrupt();
                }
                return action.get();
            }
        };
        if (pollSafepoint) {
            return TruffleSafepoint.setBlockedThreadInterruptibleFunction(context.uncachedLocation, waitAndExecuteAction, context);
        } else {
            try {
                return waitAndExecuteAction.apply(context);
            } catch (InterruptedException ie) {
                throw CompilerDirectives.shouldNotReachHere(ie);
            }
        }
    }

    static final class ThreadInitializationFailedException extends AbstractTruffleException {

        @Serial private static final long serialVersionUID = 2821425737829111224L;

        ThreadInitializationFailedException(String languageId, int nThreadFailures, Throwable cause, List<Throwable> suppressed) {
            super(String.format("The initialization of language %s failed on %d threads.", languageId, nThreadFailures), cause, UNLIMITED_STACK_TRACE, null);
            assert cause instanceof AbstractTruffleException;
            for (Throwable e : suppressed) {
                assert e instanceof AbstractTruffleException;
                addSuppressed(e);
            }
        }
    }

    boolean ensureInitialized(PolyglotLanguage accessingLanguage) {
        ensureCreated(accessingLanguage);
        if (initialized) {
            Thread initThread = initializingThread;
            if (initThread == null || initThread == Thread.currentThread()) {
                // initialized can be re-set to false during initialization
                if (initialized) {
                    // fast-path exit
                    return false;
                }
            }
        }

        if (context.finalizingEmbedderThreads) {
            throw PolyglotEngineException.illegalState(String.format(
                            "The initialization of a context for language %s is no longer allowed. Language contexts are finalized when embedder threads are being finalized.",
                            language.getId()));
        }

        if (!waitForInitializationAndThen(context.isActive(Thread.currentThread()), () -> {
            assert Thread.holdsLock(context);
            if (initialized) {
                return false;
            } else {
                PolyglotThreadInfo polyglotThreadInfo = context.getCurrentThreadInfo();
                if (!language.isHost() && polyglotThreadInfo.isFinalizationComplete()) {
                    throw PolyglotEngineException.illegalState(String.format(
                                    "The initialization of a context for language %s is no longer allowed on this thread. The thread is already finalized.", language.getId()));
                }
                initializingThread = Thread.currentThread();
                return true;
            }
        })) {
            return false;
        }
        try {
            assert !initialized;
            if (eventsEnabled) {
                EngineAccessor.INSTRUMENT.notifyLanguageContextInitialize(context.engine, context.getCreatorTruffleContext(), language.info);
            }

            try {
                Future<Void> threadInitializationFuture = null;
                boolean executeInitializeThreadDirectly = false;
                List<Throwable> initThreadErrors = Collections.synchronizedList(new ArrayList<>());
                synchronized (context) {
                    initialized = true; // Allow language use during initialization
                    if (initializationFailed) {
                        initializationFailed = false;
                        context.notifyLanguageInitializationFailureCleared();
                    }
                    context.setCachedThreadInfo(PolyglotThreadInfo.NULL);
                    context.incrementInitializedLanguagesCount();
                    if (!language.isHost()) {
                        /*
                         * Host language initialization happens before context.threadLocalActions is
                         * populated, so we can't submit thread local action.
                         */
                        if (context.hasActiveOtherThread(true, false)) {
                            threadInitializationFuture = context.threadLocalActions.submit(null, PolyglotEngineImpl.ENGINE_ID, new InitializeThreadAction(Thread.currentThread(), initThreadErrors),
                                            INITIALIZE_THREAD_HANDSHAKE_CONFIG);
                        } else {
                            executeInitializeThreadDirectly = true;
                        }
                    }
                }
                if (threadInitializationFuture != null) {
                    /*
                     * Wait for InitializeThreadAction to complete on all threads.
                     */
                    TruffleSafepoint.setBlockedThreadInterruptible(context.uncachedLocation, future -> {
                        try {
                            future.get();
                        } catch (ExecutionException e) {
                            throw CompilerDirectives.shouldNotReachHere(e);
                        }
                    }, threadInitializationFuture);
                } else if (executeInitializeThreadDirectly) {
                    initializeThreadIfNeeded(Thread.currentThread(), Thread.currentThread(), null, false, initThreadErrors);
                }
                if (!initThreadErrors.isEmpty()) {
                    boolean allExceptionsAreTruffleExceptions = true;
                    for (Throwable t : initThreadErrors) {
                        if (!(t instanceof AbstractTruffleException)) {
                            allExceptionsAreTruffleExceptions = false;
                            break;
                        }
                    }
                    RuntimeException threadInitFailedException;
                    if (allExceptionsAreTruffleExceptions) {
                        threadInitFailedException = new ThreadInitializationFailedException(language.getId(), initThreadErrors.size(), initThreadErrors.get(0),
                                        initThreadErrors.subList(1, initThreadErrors.size()));
                    } else {
                        threadInitFailedException = new IllegalStateException(
                                        String.format("The initialization of a context for language %s failed because the initializeThread call failed on %d threads.", language.getId(),
                                                        initThreadErrors.size()),
                                        initThreadErrors.get(0));
                        for (int i = 1; i < initThreadErrors.size(); i++) {
                            threadInitFailedException.addSuppressed(initThreadErrors.get(i));
                        }
                    }
                    throw threadInitFailedException;
                }
                LANGUAGE.postInitEnv(env);

            } catch (Throwable e) {
                // language not successfully initialized, reset to avoid inconsistent
                // language contexts
                synchronized (context) {
                    initialized = false;
                    assert !initializationFailed;
                    initializationFailed = true;
                    context.notifyLanguageInitializationFailure();
                    context.decrementInitializedLanguagesCount();
                }
                try {
                    if (eventsEnabled) {
                        EngineAccessor.INSTRUMENT.notifyLanguageContextInitializeFailed(context.engine, context.getCreatorTruffleContext(), language.info);
                    }
                } catch (Throwable inner) {
                    e.addSuppressed(inner);
                }
                throw e;
            }

            if (eventsEnabled) {
                EngineAccessor.INSTRUMENT.notifyLanguageContextInitialized(context.engine, context.getCreatorTruffleContext(), language.info);
            }
        } finally {
            synchronized (context) {
                initializingThread = null;
                context.notifyAll();
            }
        }

        return true;
    }

    void ensureMultiThreadingInitialized(boolean mustSucceed) {
        assert Thread.holdsLock(context);
        Lazy l = this.lazy;
        assert l != null;

        if (!l.multipleThreadsInitialized && !context.isSingleThreaded()) {
            try {
                LANGUAGE.initializeMultiThreading(env);
            } catch (Throwable t) {
                if (context.shouldThrowException(mustSucceed, t, "initializing multi-threading for")) {
                    throw t;
                }
            }
            l.multipleThreadsInitialized = true;
        }
    }

    void checkAccess(PolyglotLanguage accessingLanguage) {
        // Always check context first, as it might be invalidated.
        context.checkClosedOrDisposing(false);
        if (!context.config.isAccessPermitted(accessingLanguage, language)) {
            throw PolyglotEngineException.illegalArgument(String.format("Access to language '%s' is not permitted. ", language.getId()));
        }
        RuntimeException initError = language.initError;
        if (initError != null) {
            throw PolyglotEngineException.illegalState(String.format("Initialization error: %s", initError.getMessage(), initError));
        }
    }

    @Override
    public PolyglotEngineImpl getEngine() {
        return context.engine;
    }

    @Override
    public APIAccess getAPIAccess() {
        return context.engine.apiAccess;
    }

    @Override
    public PolyglotImpl getImpl() {
        return context.engine.impl;
    }

    boolean patch(PolyglotContextConfig newConfig) {
        Set<PolyglotLanguage> configuredLanguages = newConfig.getConfiguredLanguages();
        boolean requested = language.isHost() || language.cache.isInternal() || configuredLanguages.isEmpty() || configuredLanguages.contains(language);
        if (requested && isCreated()) {
            try {
                OptionValuesImpl newOptionValues = newConfig.getLanguageOptionValues(language).copy();
                lazy.computeAccessPermissions(newConfig);
                Env newEnv = LANGUAGE.patchEnvContext(env, newConfig.out, newConfig.err, newConfig.in,
                                Collections.emptyMap(), newOptionValues, newConfig.getApplicationArguments(language));
                if (newEnv != null) {
                    env = newEnv;
                    if (!this.language.isHost()) {
                        LOG.log(Level.FINE, "Successfully patched context of language: {0}", this.language.getId());
                    }
                    return true;
                }
                LOG.log(Level.FINE, "Failed to patch context of language: {0}", this.language.getId());
                return false;
            } catch (Throwable t) {
                LOG.log(Level.FINE, "Exception during patching context of language: {0}", this.language.getId());
                // The conversion to the host exception happens in the
                // PolyglotEngineImpl.createContext
                throw silenceException(RuntimeException.class, t);
            }
        } else {
            if (LOG.isLoggable(Level.FINER)) {
                LOG.log(Level.FINER, "The language context patching for {0} is being skipped due to requested: {1}, created: {2}.",
                                new Object[]{this.language.getId(), requested, isCreated()});
            }
            return true;
        }
    }

    @SuppressWarnings({"unchecked", "unused"})
    static <E extends Throwable> RuntimeException silenceException(Class<E> type, Throwable ex) throws E {
        throw (E) ex;
    }

    <S> S lookupService(Class<S> type) {
        for (Object languageService : languageServices) {
            if (type.isInstance(languageService)) {
                return type.cast(languageService);
            }
        }
        return null;
    }

    static final class Generic {
        private Generic() {
            throw shouldNotReachHere("no instances");
        }
    }

    @TruffleBoundary
    Object asValue(Object guestValue) {
        APIAccess api = context.getAPIAccess();
        assert lazy != null;
        assert guestValue != null;
        assert !(api.isValue(guestValue));
        assert !(api.isProxy(guestValue));
        PolyglotValueDispatch cache = getLanguageInstance().lookupValueCache(context, guestValue);
        return api.newValue(cache, this, guestValue, context.getContextAPI());
    }

    public Object toGuestValue(Node node, Object receiver) {
        return context.toGuestValue(node, receiver, false);
    }

    @GenerateInline(true)
    @GenerateCached(false)
    abstract static class ToHostValueNode extends Node {

        abstract Object execute(Node node, PolyglotLanguageContext languageContext, Object value);

        @Specialization(guards = "value.getClass() == cachedClass", limit = "3")
        Object doCached(PolyglotLanguageContext languageContext, Object value,
                        @Cached("value.getClass()") Class<?> cachedClass,
                        @Cached("lookupDispatch(languageContext, value)") PolyglotValueDispatch cachedValue) {
            Object receiver = CompilerDirectives.inInterpreter() ? value : CompilerDirectives.castExact(value, cachedClass);
            return cachedValue.impl.getAPIAccess().newValue(cachedValue, languageContext, receiver, languageContext.context.getContextAPI());
        }

        @Specialization(replaces = "doCached")
        Object doGeneric(PolyglotLanguageContext languageContext, Object value) {
            return languageContext.asValue(value);
        }

        @NeverDefault
        static PolyglotValueDispatch lookupDispatch(PolyglotLanguageContext languageContext, Object value) {
            return languageContext.lazy.languageInstance.lookupValueCache(languageContext.context, value);
        }
    }

    @SuppressWarnings("serial")
    static final class ValueMigrationException extends AbstractTruffleException {

        ValueMigrationException(String message, Node location) {
            super(message, location);
        }

    }

    @TruffleBoundary
    Object[] toHostValues(Object[] values, int startIndex) {
        Object[] args = getAPIAccess().newValueArray(values.length - startIndex);
        for (int i = startIndex; i < values.length; i++) {
            args[i - startIndex] = asValue(values[i]);
        }
        return args;
    }

    @TruffleBoundary
    Object[] toHostValues(Object[] values) {
        Object[] args = getAPIAccess().newValueArray(values.length);
        for (int i = 0; i < args.length; i++) {
            args[i] = asValue(values[i]);
        }
        return args;
    }

    @Override
    public String toString() {
        return "PolyglotLanguageContext [language=" + language + ", initialized=" + (env != null) + "]";
    }

    private final class PolyglotUncaughtExceptionHandler implements Thread.UncaughtExceptionHandler {

        @SuppressWarnings({"unused", "try"})
        @Override
        public void uncaughtException(Thread t, Throwable e) {
            if (!(e instanceof ThreadDeath)) {
                Env currentEnv = env;
                if (currentEnv != null) {
                    try (AbstractPolyglotImpl.ThreadScope scope = PolyglotLanguageContext.this.getImpl().getRootImpl().createThreadScope()) {
                        e.printStackTrace(new PrintStream(currentEnv.err()));
                    } catch (Throwable exc) {
                        // Still show the original error if printing on Env.err() fails for some
                        // reason
                        e.printStackTrace();
                    }
                } else {
                    e.printStackTrace();
                }
            }
        }
    }

    public Object getLanguageView(Object receiver) {
        EngineAccessor.INTEROP.checkInteropType(receiver);
        InteropLibrary lib = InteropLibrary.getFactory().getUncached(receiver);
        if (lib.hasLanguage(receiver)) {
            try {
                if (!this.isCreated()) {
                    throw PolyglotEngineException.illegalState("Language not yet created. Initialize the language first to request a language view.");
                }
                if (lib.getLanguage(receiver) == this.lazy.languageInstance.spi.getClass()) {
                    return receiver;
                }
            } catch (UnsupportedMessageException e) {
                throw shouldNotReachHere(e);
            }
        }
        return getLanguageViewNoCheck(receiver);
    }

    private boolean validLanguageView(Object result) {
        InteropLibrary lib = InteropLibrary.getFactory().getUncached(result);
        Class<?> languageClass = EngineAccessor.LANGUAGE.getLanguage(env).getClass();
        try {
            assert lib.hasLanguage(result) &&
                            lib.getLanguage(result) == languageClass : String.format("The returned language view of language '%s' must return the class '%s' for InteropLibrary.getLanguage." +
                                            "Fix the implementation of %s.getLanguageView to resolve this.", languageClass.getTypeName(), languageClass.getTypeName(), languageClass.getTypeName());
        } catch (UnsupportedMessageException e) {
            throw shouldNotReachHere(e);
        }
        return true;
    }

    private boolean validScopedView(Object result, Node location) {
        InteropLibrary lib = InteropLibrary.getFactory().getUncached(result);
        Class<?> languageClass = EngineAccessor.LANGUAGE.getLanguage(env).getClass();
        try {
            assert lib.hasLanguage(result) &&
                            lib.getLanguage(result) == languageClass : String.format("The returned scoped view of language '%s' must return the class '%s' for InteropLibrary.getLanguage." +
                                            "Fix the implementation of %s.getView to resolve this.", languageClass.getTypeName(), languageClass.getTypeName(), location.getClass().getTypeName());
        } catch (UnsupportedMessageException e) {
            throw shouldNotReachHere(e);
        }
        return true;
    }

    public Object getLanguageViewNoCheck(Object receiver) {
        Object result = EngineAccessor.LANGUAGE.getLanguageView(env, receiver);
        assert validLanguageView(result);
        return result;
    }

    public Object getScopedView(Node location, Frame frame, Object value) {
        validateLocationAndFrame(language.info, location, frame);
        Object languageView = getLanguageView(value);
        Object result = NodeLibrary.getUncached().getView(location, frame, languageView);
        assert validScopedView(result, location);
        return result;
    }

    private static void validateLocationAndFrame(LanguageInfo viewLanguage, Node location, Frame frame) {
        RootNode rootNode = location.getRootNode();
        if (rootNode == null) {
            throw PolyglotEngineException.illegalArgument(String.format("The location '%s' does not have a RootNode.", location));
        }
        LanguageInfo nodeLocation = rootNode.getLanguageInfo();
        if (nodeLocation == null) {
            throw PolyglotEngineException.illegalArgument(String.format("The location '%s' does not have a language associated.", location));
        }
        if (nodeLocation != viewLanguage) {
            throw PolyglotEngineException.illegalArgument(String.format("The view language '%s' must match the language of the location %s.", viewLanguage, nodeLocation));
        }
        if (!EngineAccessor.INSTRUMENT.isInstrumentable(location)) {
            throw PolyglotEngineException.illegalArgument(String.format("The location '%s' is not instrumentable but must be to request scoped views.", location));
        }
        if (!rootNode.getFrameDescriptor().equals(frame.getFrameDescriptor())) {
            throw PolyglotEngineException.illegalArgument(String.format("The frame provided does not originate from the location. " +
                            "Expected frame descriptor '%s' but was '%s'.", rootNode.getFrameDescriptor(), frame.getFrameDescriptor()));
        }
    }

    @GenerateUncached
    @GenerateInline
    abstract static class ToGuestValueNode extends Node {

        abstract Object execute(Node node, PolyglotLanguageContext context, Object receiver);

        @Specialization(guards = "receiver == null")
        static Object doNull(Node node, PolyglotLanguageContext context, @SuppressWarnings("unused") Object receiver) {
            return context.toGuestValue(node, receiver);
        }

        @Specialization(guards = {"receiver != null", "receiver.getClass() == cachedReceiver"}, limit = "3")
        static Object doCached(Node node, PolyglotLanguageContext context, Object receiver, @Cached("receiver.getClass()") Class<?> cachedReceiver) {
            return context.toGuestValue(node, cachedReceiver.cast(receiver));
        }

        @Specialization(replaces = "doCached")
        @TruffleBoundary
        static Object doUncached(Node node, PolyglotLanguageContext context, Object receiver) {
            return context.toGuestValue(node, receiver);
        }
    }

    @GenerateInline
    @GenerateCached(false)
    abstract static class ToGuestValuesNode extends Node {

        abstract Object[] execute(Node node, PolyglotLanguageContext context, Object[] args);

        @Specialization(guards = "args.length == 0")
        @SuppressWarnings("unused")
        static Object[] doZero(PolyglotLanguageContext context, Object[] args) {
            return args;
        }

        /*
         * Specialization for constant number of arguments. Uses a profile for each argument.
         */
        @ExplodeLoop
        @Specialization(replaces = {"doZero"}, guards = "args.length == toGuestValues.length", limit = "1")
        static Object[] doCached(Node node, PolyglotLanguageContext context, Object[] args,
                        @Cached("createArray(args.length)") ToGuestValueNode[] toGuestValues,
                        @Shared("needsCopy") @Cached InlinedBranchProfile needsCopyProfile) {
            boolean needsCopy = needsCopyProfile.wasEntered(node);
            Object[] newArgs = needsCopy ? new Object[toGuestValues.length] : args;
            for (int i = 0; i < toGuestValues.length; i++) {
                Object arg = args[i];
                Object newArg = toGuestValues[i].execute(toGuestValues[i], context, arg);
                if (needsCopy) {
                    newArgs[i] = newArg;
                } else if (arg != newArg) {
                    needsCopyProfile.enter(node);
                    needsCopy = true;
                    newArgs = new Object[toGuestValues.length];
                    System.arraycopy(args, 0, newArgs, 0, args.length);
                    newArgs[i] = newArg;
                }
            }
            return newArgs;
        }

        /*
         * Specialization for constant number of arguments. Uses a profile for each argument.
         */
        @Specialization(replaces = {"doZero", "doCached"})
        static Object[] doGeneric(Node node, PolyglotLanguageContext context, Object[] args,
                        @Cached ToGuestValueNode toGuest,
                        @Shared("needsCopy") @Cached InlinedBranchProfile needsCopyProfile) {

            boolean needsCopy = needsCopyProfile.wasEntered(node);
            Object[] newArgs = needsCopy ? new Object[args.length] : args;
            for (int i = 0; i < args.length; i++) {
                Object arg = args[i];
                Object newArg = toGuest.execute(node, context, arg);
                if (needsCopy) {
                    newArgs[i] = newArg;
                } else if (arg != newArg) {
                    needsCopyProfile.enter(node);
                    needsCopy = true;
                    newArgs = new Object[args.length];
                    System.arraycopy(args, 0, newArgs, 0, args.length);
                    newArgs[i] = newArg;
                }
            }
            return newArgs;
        }

        @NeverDefault
        static ToGuestValueNode[] createArray(int length) {
            ToGuestValueNode[] nodes = new ToGuestValueNode[length];
            for (int i = 0; i < nodes.length; i++) {
                nodes[i] = PolyglotLanguageContextFactory.ToGuestValueNodeGen.create();
            }
            return nodes;
        }

    }

    void patchInstance(PolyglotLanguageInstance hostInstance) {
        if (lazy != null) {
            lazy.languageInstance = hostInstance;
        }
    }

    Set<Thread> getOwnedAlivePolyglotThreads() {
        assert Thread.holdsLock(context);
        Lazy l = lazy;
        if (l != null) {
            return l.ownedAlivePolyglotThreads;
        } else {
            return null;
        }
    }

    static boolean isContextCreation(StackTraceElement[] stackTrace) {
        assert hasMethod(PolyglotLanguageContext.class, "ensureCreated");
        for (StackTraceElement element : stackTrace) {
            if (element.getClassName().equals(PolyglotLanguageContext.class.getName()) &&
                            element.getMethodName().equals("ensureCreated")) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasMethod(Class<?> klass, String methodName) {
        for (Method method : klass.getDeclaredMethods()) {
            if (method.getName().equals(methodName)) {
                return true;
            }
        }
        return false;
    }

    private class InitializeThreadAction extends ThreadLocalAction {

        private final Thread triggeringThread;
        private final List<Throwable> initThreadErrors;

        InitializeThreadAction(Thread triggeringThread, List<Throwable> initThreadErrors) {
            super(false, false);
            this.triggeringThread = triggeringThread;
            this.initThreadErrors = initThreadErrors;
        }

        @Override
        protected void perform(Access access) {
            initializeThreadIfNeeded(triggeringThread, access.getThread(), null, false, initThreadErrors);
        }
    }

    void initializeThreadIfNeeded(Thread languageContextInitializationThread, Thread currentThread, PolyglotThreadInfo currentThreadInfo, boolean mustSucceed, List<Throwable> initThreadErrors) {
        Objects.requireNonNull(currentThread);
        assert (languageContextInitializationThread == null && currentThreadInfo != null) ||
                        (languageContextInitializationThread != null && currentThreadInfo == null);
        PolyglotThreadInfo threadInfo = currentThreadInfo;
        synchronized (context) {
            if (threadInfo == null) {
                threadInfo = context.getSeenThreads().get(currentThread);
            }
            if (threadInfo.isFinalizationComplete()) {
                /*
                 * thread is already finalized, nothing to do
                 */
                return;
            }
            if (currentThread == languageContextInitializationThread) {
                PolyglotLanguageContext.this.ensureMultiThreadingInitialized(false);
            }
            if (!PolyglotLanguageContext.this.isInitialized()) {
                /*
                 * language context is not initialized, nothing to do
                 */
                return;
            }
            if (threadInfo.isLanguageContextInitialized(language)) {
                /*
                 * language context is already initialized for this thread, nothing to do
                 */
                return;
            }
            if (threadInfo.isLanguageContextInitializing(language)) {
                /*
                 * language context is already being initialized for this thread, nothing to do
                 */
                return;
            }
            threadInfo.setLanguageContextInitializing(PolyglotLanguageContext.this);
        }
        try {
            threadInfo.initializeLanguageContext(PolyglotLanguageContext.this);
            synchronized (context) {
                threadInfo.setLanguageContextInitialized(PolyglotLanguageContext.this);
            }
        } catch (Throwable t) {
            if (context.shouldThrowException(mustSucceed, t, String.format("initializing a new thread for language %s for", language.getId()))) {
                if (initThreadErrors != null) {
                    initThreadErrors.add(t);
                } else {
                    throw t;
                }
            }
        } finally {
            synchronized (context) {
                threadInfo.clearLanguageContextInitializing(PolyglotLanguageContext.this);
            }
        }
    }

}
