/*
 * Copyright 2016-2021 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license.
 */

package kotlinx.coroutines.internal

import kotlinx.atomicfu.*
import kotlinx.coroutines.*
import kotlin.coroutines.*
import kotlin.jvm.*

/**
 * Returns the first segment `s` with `s.id >= id` or `CLOSED`
 * if all the segments in this linked list have lower `id`, and the list is closed for further segment additions.
 */
internal fun <S : Segment<S>> S.findSegmentInternal(
    id: Long,
    createNewSegment: (id: Long, prev: S) -> S
): SegmentOrClosed<S> {
    /*
       Go through `next` references and add new segments if needed, similarly to the `push` in the Michael-Scott
       queue algorithm. The only difference is that "CAS failure" means that the required segment has already been
       added, so the algorithm just uses it. This way, only one segment with each id can be added.
     */
    var cur: S = this
    while (cur.id < id || cur.isRemoved) {
        val next = cur.nextOrIfClosed { return SegmentOrClosed(CLOSED) }
        if (next != null) { // there is a next node -- move there
            cur = next
            continue
        }
        val newTail = createNewSegment(cur.id + 1, cur)
        if (cur.trySetNext(newTail)) { // successfully added new node -- move there
            if (cur.isRemoved) cur.remove()
            cur = newTail
        }
    }
    return SegmentOrClosed(cur)
}

/**
 * Returns `false` if the segment `to` is logically removed, `true` on a successful update.
 */
@Suppress("NOTHING_TO_INLINE", "RedundantNullableReturnType") // Must be inline because it is an AtomicRef extension
internal inline fun <S : Segment<S>> AtomicRef<S>.moveForward(to: S): Boolean = loop { cur ->
    if (cur.id >= to.id) return true
    if (!to.tryIncPointers()) return false
    if (compareAndSet(cur, to)) { // the segment is moved
        if (cur.decPointers()) cur.remove()
        return true
    }
    if (to.decPointers()) to.remove() // undo tryIncPointers
}

/**
 * Tries to find a segment with the specified [id] following by next references from the
 * [startFrom] segment and creating new ones if needed. The typical use-case is reading this `AtomicRef` values,
 * doing some synchronization, and invoking this function to find the required segment and update the pointer.
 * At the same time, [Segment.cleanPrev] should also be invoked if the previous segments are no longer needed
 * (e.g., queues should use it in dequeue operations).
 *
 * Since segments can be removed from the list, or it can be closed for further segment additions.
 * Returns the segment `s` with `s.id >= id` or `CLOSED` if all the segments in this linked list have lower `id`,
 * and the list is closed.
 */
@Suppress("NOTHING_TO_INLINE")
internal inline fun <S : Segment<S>> AtomicRef<S>.findSegmentAndMoveForward(
    id: Long,
    startFrom: S,
    noinline createNewSegment: (id: Long, prev: S) -> S
): SegmentOrClosed<S> {
    while (true) {
        val s = startFrom.findSegmentInternal(id, createNewSegment)
        if (s.isClosed || moveForward(s.segment)) return s
    }
}

/**
 * Closes this linked list of nodes by forbidding adding new ones,
 * returns the last node in the list.
 */
internal fun <N : ConcurrentLinkedListNode<N>> N.close(): N {
    var cur: N = this
    while (true) {
        val next = cur.nextOrIfClosed { return cur }
        if (next === null) {
            if (cur.markAsClosed()) return cur
        } else {
            cur = next
        }
    }
}

internal abstract class ConcurrentLinkedListNode<N : ConcurrentLinkedListNode<N>>(prev: N?) {
    // Pointer to the next node, updates similarly to the Michael-Scott queue algorithm.
    private val _next = atomic<Any?>(null)
    // Pointer to the previous node, updates in [remove] function.
    private val _prev = atomic(prev)

    private val nextOrClosed get() = _next.value

    /**
     * Returns the next segment or `null` of the one does not exist,
     * and invokes [onClosedAction] if this segment is marked as closed.
     */
    @Suppress("UNCHECKED_CAST")
    inline fun nextOrIfClosed(onClosedAction: () -> Nothing): N? = nextOrClosed.let {
        if (it === CLOSED) {
            onClosedAction()
        } else {
            it as N?
        }
    }

    val next: N? get() = nextOrIfClosed { return null }

    /**
     * Tries to set the next segment if it is not specified and this segment is not marked as closed.
     */
    fun trySetNext(value: N): Boolean = _next.compareAndSet(null, value)

    /**
     * Checks whether this node is the physical tail of the current linked list.
     */
    val isTail: Boolean get() = next == null

    val prev: N? get() = _prev.value

    /**
     * Cleans the pointer to the previous node.
     */
    fun cleanPrev() { _prev.lazySet(null) }

    /**
     * Tries to mark the linked list as closed by forbidding adding new nodes after this one.
     */
    fun markAsClosed() = _next.compareAndSet(null, CLOSED)

    /**
     * This property indicates whether the current node is logically removed.
     * The expected use-case is removing the node logically (so that [isRemoved] becomes true),
     * and invoking [remove] after that. Note that this implementation relies on the contract
     * that the physical tail cannot be logically removed. Please, do not break this contract;
     * otherwise, memory leaks and unexpected behavior can occur.
     */
    abstract val isRemoved: Boolean

    /**
     * Removes this node physically from this linked list. The node should be
     * logically removed (so [isRemoved] returns `true`) at the point of invocation.
     */
    fun remove() {
        assert { isRemoved || isTail } // The node should be logically removed at first.
        // The physical tail cannot be removed. Instead, we remove it when
        // a new segment is added and this segment is not the tail one anymore.
        if (isTail) return
        while (true) {
            // Read `next` and `prev` pointers ignoring logically removed nodes.
            val prev = aliveSegmentLeft
            val next = aliveSegmentRight
            // Link `next` and `prev`.
            next._prev.update { if (it === null) null else prev }
            if (prev !== null) prev._next.value = next
            // Checks that prev and next are still alive.
            if (next.isRemoved && !next.isTail) continue
            if (prev !== null && prev.isRemoved) continue
            // This node is removed.
            return
        }
    }

    private val aliveSegmentLeft: N? get() {
        var cur = prev
        while (cur !== null && cur.isRemoved)
            cur = cur._prev.value
        return cur
    }

    private val aliveSegmentRight: N get() {
        assert { !isTail } // Should not be invoked on the tail node
        var cur = next!!
        while (cur.isRemoved)
            cur = cur.next ?: return cur
        return cur
    }
}

/**
 * Each segment in the list has a unique id and is created by the provided to [findSegmentAndMoveForward] method.
 * Essentially, this is a node in the Michael-Scott queue algorithm,
 * but with maintaining [prev] pointer for efficient [remove] implementation.
 *
 * NB: this class cannot be public or leak into user's code as public type as [CancellableContinuationImpl]
 * instance-check it and uses a separate code-path for that.
 */
internal abstract class Segment<S : Segment<S>>(
    @JvmField val id: Long, prev: S?, pointers: Int
) : ConcurrentLinkedListNode<S>(prev),
    // Segments typically store waiting continuations. Thus, on cancellation, the corresponding
    // slot should be cleaned and the segment should be removed if it becomes full of cancelled cells.
    // To install such a handler efficiently, without creating an extra object, we allow storing
    // segments as cancellation handlers in [CancellableContinuationImpl] state, putting the slot
    // index in another field. The details are here: https://github.com/Kotlin/kotlinx.coroutines/pull/3084.
    // For that, we need segments to implement this internal marker interface.
    NotCompleted
{
    /**
     * This property should return the number of slots in this segment,
     * it is used to define whether the segment is logically removed.
     */
    abstract val numberOfSlots: Int

    /**
     * Numbers of cleaned slots (the lowest bits) and AtomicRef pointers to this segment (the highest bits)
     */
    private val cleanedAndPointers = atomic(pointers shl POINTERS_SHIFT)

    /**
     * The segment is considered as removed if all the slots are cleaned
     * and there are no pointers to this segment from outside.
     */
    override val isRemoved get() = cleanedAndPointers.value == numberOfSlots && !isTail

    // increments the number of pointers if this segment is not logically removed.
    internal fun tryIncPointers() = cleanedAndPointers.addConditionally(1 shl POINTERS_SHIFT) { it != numberOfSlots || isTail }

    // returns `true` if this segment is logically removed after the decrement.
    internal fun decPointers() = cleanedAndPointers.addAndGet(-(1 shl POINTERS_SHIFT)) == numberOfSlots && !isTail

    /**
     * This function is invoked on continuation cancellation when this segment
     * with the specified [index] are installed as cancellation handler via
     * `SegmentDisposable.disposeOnCancellation(Segment, Int)`.
     *
     * @param index the index under which the sement registered itself in the continuation.
     *        Indicies are opaque and arithmetics or numeric intepretation is not allowed on them,
     *        as they may encode additional metadata.
     * @param cause the cause of the cancellation, with the same semantics as [CancellableContinuation.invokeOnCancellation]
     * @param context the context of the cancellable continuation the segment was registered in
     */
    abstract fun onCancellation(index: Int, cause: Throwable?, context: CoroutineContext)

    /**
     * Invoked on each slot clean-up; should not be invoked twice for the same slot.
     */
    fun onSlotCleaned() {
        if (cleanedAndPointers.incrementAndGet() == numberOfSlots) remove()
    }
}

private inline fun AtomicInt.addConditionally(delta: Int, condition: (cur: Int) -> Boolean): Boolean {
    while (true) {
        val cur = this.value
        if (!condition(cur)) return false
        if (this.compareAndSet(cur, cur + delta)) return true
    }
}

@JvmInline
internal value class SegmentOrClosed<S : Segment<S>>(private val value: Any?) {
    val isClosed: Boolean get() = value === CLOSED
    @Suppress("UNCHECKED_CAST")
    val segment: S get() = if (value === CLOSED) error("Does not contain segment") else value as S
}

private const val POINTERS_SHIFT = 16

private val CLOSED = Symbol("CLOSED")
