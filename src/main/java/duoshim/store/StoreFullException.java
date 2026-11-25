package duoshim.store;

/** Thrown when a store is at capacity even after sweeping expired entries. */
public final class StoreFullException extends Exception {

    private static final long serialVersionUID = 1L;

    public StoreFullException(String storeName) {
        super("store '" + storeName + "' is full");
    }
}
