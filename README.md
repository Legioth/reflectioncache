# reflectioncache
A thread-safe reflection result cache that doesn't prevent class loaders from being garbage collected.

## Why?
With a regular `ConcurrentHashMap`, both the keys and the values would hold strong references to the class loader, thus causing a memory leak when the class loader is no longer used. Using `WeakReference` to avoid the leak would instead mean that any value in the cache might be garbage collected at any time even though the key is still in use.

## How?
This implementation works around the `WeakReference` issue by creating a hard reference in a static field of a class injected to the owning class loader. The reference through this field does thus prevent the value from being garbage collected, but it does not prevent the class loader from being collected when there are no more references to any objects originated from that class loader.

