package org.vaadin.reflectioncache;

import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import net.bytebuddy.ByteBuddy;
import net.bytebuddy.dynamic.loading.ClassLoadingStrategy;

import org.junit.Assert;
import org.junit.Test;

public class ReflectionCacheTest {
    private static final class CountingProducer implements
            Function<Class<?>, Integer> {
        private AtomicInteger value = new AtomicInteger();

        @Override
        public Integer apply(Class<?> t) {
            return Integer.valueOf(value.incrementAndGet());
        }
    }

    public static class ObjectWithField {
        public Object value;
    }

    private final CountingProducer countingProducer = new CountingProducer();

    private final Function<Class<?>, Field> valueFieldProducer = type -> {
        try {
            return type.getField("value");
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    };

    @Test
    public void testBasicOperation() {
        ReflectionCache<Integer> cache = new ReflectionCache<>(countingProducer);

        Integer value = cache.get(ReflectionCacheTest.class);
        Assert.assertEquals(1, value.intValue());

        Integer otherValue = cache.get(ReflectionCache.class);
        Assert.assertEquals(2, otherValue.intValue());

        Integer newValue = cache.get(ReflectionCacheTest.class);
        Assert.assertSame("Should still get the same value", value, newValue);
    }

    @Test
    public void testBootstrapClassOperation() {
        ReflectionCache<Integer> cache = new ReflectionCache<>(countingProducer);

        Integer value = cache.get(Object.class);
        Assert.assertEquals(1, value.intValue());

        Integer newValue = cache.get(Object.class);
        Assert.assertSame(value, newValue);
    }

    @Test
    public void testForeignClassLoader() throws Exception {
        ReflectionCache<Field> cache = new ReflectionCache<>(valueFieldProducer);

        Object instance = createObjectInOtherClassLoader();

        // Used verify the class loader has been garbage collected later on
        WeakReference<ClassLoader> clRef = new WeakReference<>(instance
                .getClass().getClassLoader());

        WeakReference<Field> ref = new WeakReference<>(cache.get(instance
                .getClass()));

        Assert.assertFalse(
                "Field should not be garbage collected while class loader is live",
                isGarbageCollected(ref));

        // Query with the same key again
        Assert.assertSame("Field should still be in the cache", ref.get(),
                cache.get(instance.getClass()));

        // Release the last reference to anything related to the class loader
        instance = null;
        Assert.assertTrue(
                "Class loader should be garbage collected when instance is no longer live",
                isGarbageCollected(clRef));

        Assert.assertTrue(
                "Field should be garbage collected when class loader is no longer live",
                isGarbageCollected(ref));
    }

    @Test
    public void testFinalizeReleasesValuesInOwnClassLoader()
            throws InterruptedException {
        ReflectionCache<Field> cache = new ReflectionCache<>(valueFieldProducer);

        Object instance = new ObjectWithField();

        WeakReference<Field> ref = new WeakReference<>(cache.get(instance
                .getClass()));

        Assert.assertFalse(
                "Field should not be garbage collected while cache is live",
                isGarbageCollected(ref));

        cache = null;

        Assert.assertTrue(
                "Field should be garbage collected when cache is no longer live",
                isGarbageCollected(ref));
    }

    @Test
    public void testFinalizeReleasesValuesInOtherClassLoader() throws Exception {
        ReflectionCache<Field> cache = new ReflectionCache<>(valueFieldProducer);

        Object instance = createObjectInOtherClassLoader();

        WeakReference<Field> ref = new WeakReference<>(cache.get(instance
                .getClass()));

        Assert.assertFalse(
                "Field should not be garbage collected while cache is live",
                isGarbageCollected(ref));

        cache = null;

        Assert.assertTrue(
                "Field should be garbage collected when cache is no longer live",
                isGarbageCollected(ref));
    }

    @SuppressWarnings("unused")
    @Test(expected = IllegalArgumentException.class)
    public void testNullConstructorParameter() {
        new ReflectionCache<Object>(null);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testNullGetParameter() {
        ReflectionCache<Integer> cache = new ReflectionCache<>(countingProducer);
        cache.get(null);
    }

    private static Object createObjectInOtherClassLoader()
            throws InstantiationException, IllegalAccessException {
        return new ByteBuddy().subclass(Object.class)
                .defineField("value", Object.class, Modifier.PUBLIC).make()
                .load(null, ClassLoadingStrategy.Default.WRAPPER).getLoaded()
                .newInstance();
    }

    private static boolean isGarbageCollected(WeakReference<?> ref)
            throws InterruptedException {
        for (int i = 0; i < 10; i++) {
            System.gc();
            if (ref.get() == null) {
                return true;
            }
            Thread.sleep(10);
        }

        return false;
    }
}
