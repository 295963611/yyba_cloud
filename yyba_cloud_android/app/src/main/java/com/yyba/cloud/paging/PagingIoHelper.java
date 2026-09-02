package com.yyba.cloud.paging;

import androidx.annotation.NonNull;

import kotlin.coroutines.Continuation;
import kotlinx.coroutines.BuildersKt;
import kotlinx.coroutines.CoroutineScope;
import kotlinx.coroutines.Dispatchers;

/** Java PagingSource.load 默认可能在主线程同步执行，统一切到 IO。 */
public final class PagingIoHelper {

    @FunctionalInterface
    public interface Block<T> {
        T invoke() throws Exception;
    }

    private PagingIoHelper() {
    }

    public static <T> Object withIo(@NonNull Block<T> block,
                                    @NonNull Continuation<? super T> continuation) {
        return BuildersKt.withContext(Dispatchers.getIO(),
                (CoroutineScope scope, Continuation<? super T> cont) -> {
                    try {
                        return block.invoke();
                    } catch (Exception e) {
                        if (e instanceof RuntimeException) {
                            throw (RuntimeException) e;
                        }
                        throw new RuntimeException(e);
                    }
                },
                continuation);
    }
}
