/*
 * Copyright (c) 2015, 2025, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */
package com.oracle.svm.core.jdk;

import static java.util.Locale.Category.DISPLAY;
import static java.util.Locale.Category.FORMAT;
import static jdk.graal.compiler.nodes.extended.MembarNode.FenceKind.STORE_STORE;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

import org.graalvm.nativeimage.ImageInfo;
import org.graalvm.nativeimage.ImageSingletons;
import org.graalvm.nativeimage.Platform;
import org.graalvm.nativeimage.Platforms;
import org.graalvm.nativeimage.c.type.CCharPointerPointer;
import org.graalvm.nativeimage.c.type.CTypeConversion;
import org.graalvm.nativeimage.impl.RuntimeSystemPropertiesSupport;

import com.oracle.svm.core.LibCHelper;
import com.oracle.svm.core.SubstrateUtil;
import com.oracle.svm.core.VM;
import com.oracle.svm.core.config.ConfigurationValues;
import com.oracle.svm.core.headers.LibCSupport;
import com.oracle.svm.core.util.VMError;

import jdk.graal.compiler.api.replacements.Fold;
import jdk.graal.compiler.debug.GraalError;
import jdk.graal.compiler.nodes.extended.MembarNode;

/**
 * This class maintains the system properties at run time.
 *
 * Some of the standard system properties can just be taken from the image generator, see
 * {@link #HOSTED_PROPERTIES}. Other system properties need to be computed at run time. However, we
 * want to do the computation lazily to reduce the startup cost. For example, getting the current
 * working directory is quite expensive. We initialize such a property either when it is explicitly
 * accessed, or when all properties are accessed.
 */
public abstract class SystemPropertiesSupport implements RuntimeSystemPropertiesSupport {

    /** System properties that are taken from the VM hosting the image generator. */
    private static final String[] HOSTED_PROPERTIES = {
                    "java.version",
                    "java.version.date",
                    ImageInfo.PROPERTY_IMAGE_KIND_KEY,
                    /*
                     * We do not support cross-compilation for now. Separators might also be cached
                     * in other classes, so changing them would be tricky.
                     */
                    "line.separator",
                    "path.separator",
                    "file.separator",
                    /* For our convenience for now. */
                    "file.encoding",
                    "sun.jnu.encoding",
                    "native.encoding",
                    "stdout.encoding",
                    "stderr.encoding",
                    "java.class.version",
                    "java.runtime.version",
                    "java.specification.name",
                    "java.specification.vendor",
                    "java.specification.version",
                    "java.vm.specification.name",
                    "java.vm.specification.vendor",
                    "java.vm.specification.version"
    };

    /* The list of field positions in locale_props_t (see locale_str.h). */
    private static final int LANGUAGE_POSITION = 0;
    private static final int SCRIPT_POSITION = LANGUAGE_POSITION + 1;
    private static final int COUNTRY_POSITION = SCRIPT_POSITION + 1;
    private static final int VARIANT_POSITION = COUNTRY_POSITION + 1;
    private static final int EXTENSION_POSITION = VARIANT_POSITION + 1;

    /** System properties that are computed at run time on first access. */
    private final Map<String, LazySystemProperty> lazySystemProperties = new HashMap<>();
    /**
     * Initial system property values after parsing command line options at run time. Changes by the
     * application (e.g., via {@link System#setProperties}) do not affect this map. Note that this
     * map must not contain any null values (see usages on the libgraal-side).
     */
    private final Map<String, String> initialProperties = new ConcurrentHashMap<>();
    /** Read-only wrapper for the initial system property values. */
    private final Map<String, String> readOnlyInitialProperties = Collections.unmodifiableMap(initialProperties);

    private Properties currentProperties = new Properties();
    private boolean allPropertiesInitialized;

    @Fold
    public static SystemPropertiesSupport singleton() {
        return ImageSingletons.lookup(SystemPropertiesSupport.class);
    }

    @Platforms(Platform.HOSTED_ONLY.class)
    @SuppressWarnings("this-escape")
    protected SystemPropertiesSupport() {
        for (String key : HOSTED_PROPERTIES) {
            String value = System.getProperty(key);
            if (value != null) {
                initializeProperty(key, value);
            }
        }

        initializeProperty("java.runtime.name", "GraalVM Runtime Environment");

        VM vm = ImageSingletons.lookup(VM.class);
        initializeProperty("java.vendor", vm.vendor);
        initializeProperty("java.vendor.url", vm.vendorUrl);
        initializeProperty("java.vendor.version", vm.vendorVersion);
        assert vm.info.equals(vm.info.toLowerCase(Locale.ROOT)) : "java.vm.info should not contain uppercase characters";
        initializeProperty("java.vm.info", vm.info);
        initializeProperty("java.vm.name", "Substrate VM");
        initializeProperty("java.vm.vendor", vm.vendor);
        initializeProperty("java.vm.version", vm.version);

        initializeProperty("java.class.path", "");
        initializeProperty("java.endorsed.dirs", "");
        initializeProperty("java.ext.dirs", "");
        initializeProperty("sun.arch.data.model", Integer.toString(ConfigurationValues.getTarget().wordJavaKind.getBitCount()));

        initializeProperty(ImageInfo.PROPERTY_IMAGE_CODE_KEY, ImageInfo.PROPERTY_IMAGE_CODE_VALUE_RUNTIME);

        ArrayList<LazySystemProperty> lazyProperties = new ArrayList<>();
        lazyProperties.add(new LazySystemProperty(UserSystemProperty.NAME, this::userNameValue));
        lazyProperties.add(new LazySystemProperty(UserSystemProperty.HOME, this::userHomeValue));
        lazyProperties.add(new LazySystemProperty(UserSystemProperty.DIR, this::userDirValue));
        lazyProperties.add(new LazySystemProperty("java.io.tmpdir", this::javaIoTmpdirValue));
        lazyProperties.add(new LazySystemProperty("java.library.path", this::javaLibraryPathValue));
        lazyProperties.add(new LazySystemProperty("os.version", this::osVersionValue));
        lazyProperties.add(new LazySystemProperty(UserSystemProperty.LANGUAGE, () -> postProcessLocale(UserSystemProperty.LANGUAGE, parseLocale(DISPLAY).language(), null)));
        lazyProperties.add(new LazySystemProperty(UserSystemProperty.LANGUAGE_DISPLAY, () -> postProcessLocale(UserSystemProperty.LANGUAGE, parseLocale(DISPLAY).language(), DISPLAY)));
        lazyProperties.add(new LazySystemProperty(UserSystemProperty.LANGUAGE_FORMAT, () -> postProcessLocale(UserSystemProperty.LANGUAGE, parseLocale(FORMAT).language(), FORMAT)));
        lazyProperties.add(new LazySystemProperty(UserSystemProperty.SCRIPT, () -> postProcessLocale(UserSystemProperty.SCRIPT, parseLocale(DISPLAY).script(), null)));
        lazyProperties.add(new LazySystemProperty(UserSystemProperty.SCRIPT_DISPLAY, () -> postProcessLocale(UserSystemProperty.SCRIPT, parseLocale(DISPLAY).script(), DISPLAY)));
        lazyProperties.add(new LazySystemProperty(UserSystemProperty.SCRIPT_FORMAT, () -> postProcessLocale(UserSystemProperty.SCRIPT, parseLocale(FORMAT).script(), FORMAT)));
        lazyProperties.add(new LazySystemProperty(UserSystemProperty.COUNTRY, () -> postProcessLocale(UserSystemProperty.COUNTRY, parseLocale(DISPLAY).country(), null)));
        lazyProperties.add(new LazySystemProperty(UserSystemProperty.COUNTRY_DISPLAY, () -> postProcessLocale(UserSystemProperty.COUNTRY, parseLocale(DISPLAY).country(), DISPLAY)));
        lazyProperties.add(new LazySystemProperty(UserSystemProperty.COUNTRY_FORMAT, () -> postProcessLocale(UserSystemProperty.COUNTRY, parseLocale(FORMAT).country(), FORMAT)));
        lazyProperties.add(new LazySystemProperty(UserSystemProperty.VARIANT, () -> postProcessLocale(UserSystemProperty.VARIANT, parseLocale(FORMAT).country(), FORMAT)));
        lazyProperties.add(new LazySystemProperty(UserSystemProperty.VARIANT_DISPLAY, () -> postProcessLocale(UserSystemProperty.VARIANT, parseLocale(DISPLAY).variant(), DISPLAY)));
        lazyProperties.add(new LazySystemProperty(UserSystemProperty.VARIANT_FORMAT, () -> postProcessLocale(UserSystemProperty.VARIANT, parseLocale(FORMAT).variant(), FORMAT)));
        lazyProperties.add(new LazySystemProperty(UserSystemProperty.EXTENSIONS, () -> postProcessLocale(UserSystemProperty.EXTENSIONS, parseLocale(DISPLAY).extensions(), null)));
        lazyProperties.add(new LazySystemProperty(UserSystemProperty.EXTENSIONS_DISPLAY, () -> postProcessLocale(UserSystemProperty.EXTENSIONS, parseLocale(DISPLAY).extensions(), DISPLAY)));
        lazyProperties.add(new LazySystemProperty(UserSystemProperty.EXTENSIONS_FORMAT, () -> postProcessLocale(UserSystemProperty.EXTENSIONS, parseLocale(FORMAT).extensions(), FORMAT)));

        String targetName = System.getProperty("svm.targetName");
        if (targetName != null) {
            initializeProperty("os.name", targetName);
        } else {
            lazyProperties.add(new LazySystemProperty("os.name", this::osNameValue));
        }

        String targetArch = System.getProperty("svm.targetArch");
        if (targetArch != null) {
            initializeProperty("os.arch", targetArch);
        } else if (Platform.includedIn(Platform.DARWIN.class)) {
            /* On Darwin, we need to use the hosted value to be consistent with HotSpot. */
            initializeProperty("os.arch", System.getProperty("os.arch"));
        } else {
            initializeProperty("os.arch", ImageSingletons.lookup(Platform.class).getArchitecture());
        }

        /* Register all lazy properties. */
        for (LazySystemProperty property : lazyProperties) {
            assert !initialProperties.containsKey(property.getKey());
            assert !currentProperties.containsKey(property.getKey());

            lazySystemProperties.put(property.getKey(), property);
        }
    }

    /**
     * Initializes a system property at build-time or during VM startup from external input (e.g.,
     * command line arguments).
     */
    @Override
    public void initializeProperty(String key, String value) {
        VMError.guarantee(key != null, "The key for a system property must not be null.");
        VMError.guarantee(value != null, "System property must have a non-null value.");

        initialProperties.put(key, value);
        currentProperties.setProperty(key, value);

        /*
         * If there is a lazy system property, then mark it as initialized to ensure that the lazy
         * initialization code is not executed at run-time.
         */
        LazySystemProperty property = lazySystemProperties.get(key);
        if (property != null) {
            property.markAsInitialized();
        }
    }

    public Map<String, String> getInitialProperties() {
        ensureAllPropertiesInitialized();
        return readOnlyInitialProperties;
    }

    public String getInitialProperty(String key) {
        return getInitialProperty(key, true);
    }

    public String getInitialProperty(String key, boolean initializeLazyProperty) {
        if (initializeLazyProperty) {
            ensurePropertyInitialized(key);
        }
        return initialProperties.get(key);
    }

    public String getInitialProperty(String key, String defaultValue) {
        String value = getInitialProperty(key);
        return value != null ? value : defaultValue;
    }

    public Properties getCurrentProperties() {
        ensureAllPropertiesInitialized();
        return currentProperties;
    }

    public void setCurrentProperties(Properties props) {
        ensureAllPropertiesInitialized();
        if (props == null) {
            /* Reset to initial values. */
            Properties newProps = new Properties();
            for (Map.Entry<String, String> e : initialProperties.entrySet()) {
                String value = e.getValue();
                newProps.setProperty(e.getKey(), value);
            }
            currentProperties = newProps;
        } else {
            currentProperties = props;
        }
    }

    protected String getCurrentProperty(String key) {
        ensurePropertyInitialized(key);
        return currentProperties.getProperty(key);
    }

    protected String getCurrentProperty(String key, String defaultValue) {
        String value = getCurrentProperty(key);
        return value != null ? value : defaultValue;
    }

    public String setCurrentProperty(String key, String value) {
        ensurePropertyInitialized(key);
        return (String) currentProperties.setProperty(key, value);
    }

    public String clearCurrentProperty(String key) {
        ensurePropertyInitialized(key);
        return (String) currentProperties.remove(key);
    }

    private void ensureAllPropertiesInitialized() {
        if (!allPropertiesInitialized) {
            initializeAllProperties();
        }
    }

    private synchronized void initializeAllProperties() {
        if (allPropertiesInitialized) {
            return;
        }

        for (var entry : lazySystemProperties.entrySet()) {
            LazySystemProperty property = entry.getValue();
            initializeProperty(property);
        }

        /*
         * No memory barrier is needed because the loop above already emits one STORE_STORE barrier
         * per initialized system property.
         */
        allPropertiesInitialized = true;
    }

    private void ensurePropertyInitialized(String key) {
        if (allPropertiesInitialized) {
            return;
        }

        LazySystemProperty property = lazySystemProperties.get(key);
        if (property != null) {
            ensureInitialized(property);
        }
    }

    private void ensureInitialized(LazySystemProperty property) {
        if (!property.isInitialized()) {
            initializeProperty(property);
        }
    }

    private synchronized void initializeProperty(LazySystemProperty property) {
        if (property.isInitialized()) {
            return;
        }

        String key = property.getKey();
        String value = property.computeValue();
        if (value != null) {
            currentProperties.put(key, value);
            initialProperties.put(key, value);
        }

        /* Publish the value. */
        property.markAsInitialized();
    }

    // Platform-specific subclasses compute the actual values lazily at run time.

    protected abstract String userNameValue();

    protected abstract String userHomeValue();

    protected abstract String userDirValue();

    protected abstract String osNameValue();

    protected abstract String osVersionValue();

    protected String javaIoTmpdirValue() {
        return tmpdirValue();
    }

    /* Should be removed, see GR-61420. */
    protected String tmpdirValue() {
        throw VMError.shouldNotReachHere("Subclasses must either implement javaIoTmpdirValue() or tmpdirValue().");
    }

    /* Should be removed, see GR-61420. */
    protected String javaLibraryPathValue() {
        /* Fallback for platforms that don't implement this method. */
        return "";
    }

    public record LocaleEncoding(String language, String script, String country, String variant, String extensions) {
        private LocaleEncoding(CCharPointerPointer properties) {
            this(fromCStringArray(properties, LANGUAGE_POSITION),
                            fromCStringArray(properties, SCRIPT_POSITION),
                            fromCStringArray(properties, COUNTRY_POSITION),
                            fromCStringArray(properties, VARIANT_POSITION),
                            fromCStringArray(properties, EXTENSION_POSITION));
        }

        private static String fromCStringArray(CCharPointerPointer cString, int index) {
            if (cString.isNull()) {
                return null;
            }
            return CTypeConversion.toJavaString(cString.read(index));
        }
    }

    private LocaleEncoding displayLocale;

    private LocaleEncoding formatLocale;

    protected LocaleEncoding parseLocale(Locale.Category category) {
        if (!ImageSingletons.contains(LibCSupport.class)) {
            /* If native calls are not supported, just return fixed values. */
            return new LocaleEncoding("en", "", "US", "", "");
        }
        switch (category) {
            case DISPLAY -> {
                if (displayLocale == null) {
                    displayLocale = new LocaleEncoding(LibCHelper.Locale.parseDisplayLocale());
                }
                return displayLocale;
            }
            case FORMAT -> {
                if (formatLocale == null) {
                    formatLocale = new LocaleEncoding(LibCHelper.Locale.parseFormatLocale());
                }
                return formatLocale;
            }
            default -> throw new GraalError("Unknown locale category: " + category + ".");
        }
    }

    private String postProcessLocale(String base, String value, Locale.Category category) {
        if (category == null) {
            /* user.xxx property */
            String baseValue = null;
            if (value != null) {
                initializeProperty(base, value);
                baseValue = value;
            }
            return baseValue;
        }
        switch (category) {
            case DISPLAY, FORMAT -> {
                /* user.xxx.(display|format) property */
                String baseValue = getCurrentProperty(base);
                if (baseValue == null && value != null) {
                    initializeProperty(base + '.' + category.name().toLowerCase(Locale.ROOT), value);
                    return value;
                }
                return null;
            }
            default -> throw new GraalError("Unknown locale category: " + category + ".");
        }
    }

    private static class LazySystemProperty {
        private final String key;
        private final Supplier<String> supplier;

        private boolean initialized;

        @Platforms(Platform.HOSTED_ONLY.class)
        LazySystemProperty(String key, Supplier<String> supplier) {
            this.key = key;
            this.supplier = supplier;
        }

        public String getKey() {
            return key;
        }

        public boolean isInitialized() {
            return initialized;
        }

        public String computeValue() {
            return supplier.get();
        }

        public void markAsInitialized() {
            if (!SubstrateUtil.HOSTED) {
                /* Ensure that other threads see consistent values once 'initialized' is true. */
                MembarNode.memoryBarrier(STORE_STORE);
            }
            initialized = true;
        }
    }
}
