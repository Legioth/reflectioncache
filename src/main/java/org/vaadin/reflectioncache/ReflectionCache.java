package org.vaadin.reflectioncache;

import java.lang.ref.WeakReference;
import java.lang.reflect.Modifier;
import java.util.HashSet;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Function;

import net.bytebuddy.ByteBuddy;
import net.bytebuddy.dynamic.loading.ClassLoadingStrategy;

/**
 * A thread-safe reflection result cache that doesn't prevent class loaders from
 * being garbage collected. With a regular {@link ConcurrentHashMap}, both the
 * keys and the values would hold strong references to the class loader, thus
 * causing a memory leak when the class loader is no longer used. Using
 * {@link WeakReference} to avoid the leak would instead mean that any value in
 * the cache might be garbage collected at any time even though the key is still
 * in use.
 * <p>
 * This implementation works around the {@code WeakReference} issue by creating
 * a hard reference in a static field of a class injected to the owning class
 * loader. The reference through this field does thus prevent the value from
 * being garbage collected, but it does not prevent the class loader from being
 * collected when there are no more references to any objects originated from
 * that class loader.
 *
 * @param <T>
 *            the type of the stored values
 */
public class ReflectionCache<T> {
    /**
     * Name of the static field used in the generated class.
     */
    private static final String FIELD_NAME = "value";

    private static final int PUBLIC_STATIC_MODIFIER = Modifier.PUBLIC
            | Modifier.STATIC;

    /**
     * Weak map from a class loader to a map of values cached for classes loaded
     * by that class loader. Updates to the map are done using copy-on-write to
     * enable concurrent lock-free reading. {@link #classLoaderMapUpdateLock} is
     * used to protect against concurrent modification.
     */
    private volatile WeakHashMap<ClassLoader, WeakReference<ConcurrentMap<Class<?>, T>>> classLoaders = new WeakHashMap<>();

    /**
     * Lock used when updating {@link #classLoaders}.
     */
    private final Object classLoaderMapUpdateLock = new Object();

    /**
     * Cache for classes loaded by the class loader of this class as well as all
     * ancestor class loaders. All those class loaders will be live as long as
     * this instance is also live, so there's no benefit from using weak
     * references. Furthermore, it is not possible to inject classes into the
     * bootstrap class loader.
     */
    private final ConcurrentMap<Class<?>, T> ownValues = new ConcurrentHashMap<>();

    /**
     * The class loaders for which {@link #ownValues} should be used.
     */
    private final Set<ClassLoader> ancestorClassLoaders = new HashSet<>();

    /**
     * Function for generating a value if there is a cache miss.
     */
    private final Function<Class<?>, T> producer;

    /**
     * Creates a new cache instance with the given value producer.
     *
     * @param producer
     *            function for generating a value if there is a cache miss, not
     *            null
     */
    public ReflectionCache(Function<Class<?>, T> producer) {
        if (producer == null) {
            throw new IllegalArgumentException("producer cannot be null");
        }

        this.producer = producer;

        // Find all ancestor class loaders
        ClassLoader classLoader = getClass().getClassLoader();
        while (classLoader != null) {
            ancestorClassLoaders.add(classLoader);
            classLoader = classLoader.getParent();
        }
        // Bootstrap class loader is null in some JVM implementations
        ancestorClassLoaders.add(null);
    }

    /**
     * Gets, or creates, a cached value for the given type. If a cached value is
     * not found, a new value is generated using the callback passed to
     * {@link #ReflectionCache(Function)}.
     *
     * @param type
     *            the class to find a cached value for, not null
     * @return the cached value for the provided class
     */
    public T get(Class<?> type) {
        if (type == null) {
            throw new IllegalArgumentException("type cannot be null");
        }

        // Find the map for storing values belonging to the type's class loader
        ConcurrentMap<Class<?>, T> cache = findCache(type.getClassLoader());

        // Get or compute the cached value
        return cache.computeIfAbsent(type, producer);
    }

    /**
     * Finds or creates a cache for the given class loader.
     *
     * @param classLoader
     *            the class loader to get a cache for
     * @return a map for caching values related to the given class loader
     */
    private ConcurrentMap<Class<?>, T> findCache(ClassLoader classLoader) {
        // Use an instance field for class loaders that can't leak
        if (ancestorClassLoaders.contains(classLoader)) {
            return ownValues;
        }

        ConcurrentMap<Class<?>, T> cache;

        // Look for an existing entry in the current classLoaders map
        WeakReference<ConcurrentMap<Class<?>, T>> reference = classLoaders
                .get(classLoader);

        if (reference != null) {
            cache = reference.get();
        } else {
            // Copy-on-write to create an entry for this class loader

            // Critical section to avoid creating duplicate entries
            synchronized (classLoaderMapUpdateLock) {
                // Local copy to reduce the number of volatile reads
                WeakHashMap<ClassLoader, WeakReference<ConcurrentMap<Class<?>, T>>> currentClassLoaders = classLoaders;

                if (currentClassLoaders.containsKey(classLoader)) {
                    // Entry was created while we waited for the lock
                    cache = currentClassLoaders.get(classLoader).get();
                } else {
                    cache = new ConcurrentHashMap<>();

                    // Create strong reference from class loader to cache
                    attachToClassloader(classLoader, cache);

                    // Create a copy of the class loader map
                    currentClassLoaders = new WeakHashMap<>(currentClassLoaders);

                    // Update the new map
                    currentClassLoaders.put(classLoader, new WeakReference<>(
                            cache));

                    // Put new map instance into use
                    classLoaders = currentClassLoaders;
                }
            }
        }

        if (cache == null) {
            throw new RuntimeException(
                    "WeakReference was cleared even though class loader is still alive. This should never happen?");
        }

        return cache;
    }

    /**
     * Attaches a value inside a class loader to so that the value doesn't get
     * garbage collected as long as the class loader is live, while still not
     * preventing the class loader from getting collected even if the value
     * contains strong references to the class loader.
     *
     * @param classLoader
     *            the class loader to attach the value to
     * @param value
     *            the value to attache the class loader to
     */
    private static void attachToClassloader(ClassLoader classLoader,
            Object value) {
        assert classLoader != null;
        assert value != null;

        // Generate class with static field and inject it into the class loader
        Class<? extends Object> injectedClass = new ByteBuddy()
                .subclass(Object.class)
                .defineField(FIELD_NAME, Object.class, PUBLIC_STATIC_MODIFIER)
                .make()
                .load(classLoader, ClassLoadingStrategy.Default.INJECTION)
                .getLoaded();

        try {
            // Store the value inside the static field
            injectedClass.getField(FIELD_NAME).set(null, value);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Removes all mappings from this cache.
     */
    public void clean() {
        ownValues.clear();

        /*
         * We won't clear the actual classLoaders map since we would then have
         * to create and inject new classes into each class loader.
         */
        classLoaders.values().stream().map(WeakReference::get)
                .filter(m -> m != null).forEach(ConcurrentMap::clear);

        /*
         * XXX This still leaves an empty ConcurrentClassMap in each class
         * loader. Getting rid of that would require keeping a (weak) reference
         * to the Field where the map is stored.
         */
    }

    @Override
    protected void finalize() throws Throwable {
        // Remove all strong references injected into other class loaders
        clean();

        super.finalize();
    }
}
