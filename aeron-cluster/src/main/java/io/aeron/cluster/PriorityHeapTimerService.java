/*
 * Copyright 2014-2021 Real Logic Limited.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.aeron.cluster;

import org.agrona.collections.ArrayUtil;
import org.agrona.collections.Long2ObjectHashMap;

import java.util.Arrays;
import java.util.Objects;

final class PriorityHeapTimerService implements TimerService
{
    private static final TimerEntry[] NO_TIMERS = new TimerEntry[0];
    private static final int MIN_CAPACITY = 8;

    private final TimerHandler timerHandler;
    private final Long2ObjectHashMap<TimerEntry> timerByCorrelationId = new Long2ObjectHashMap<>();
    private TimerEntry[] timers = NO_TIMERS;
    private int size;

    PriorityHeapTimerService(final TimerHandler timerHandler)
    {
        this.timerHandler = Objects.requireNonNull(timerHandler, "TimerHandler");
    }

    public int poll(final long now)
    {
        int expiredTimers = 0;
        final TimerEntry[] timers = this.timers;
        final TimerHandler timerHandler = this.timerHandler;

        while (size > 0 && expiredTimers < POLL_LIMIT)
        {
            final TimerEntry timer = timers[0];
            if (timer.deadline > now)
            {
                break;
            }

            if (!timerHandler.onTimerEvent(timer.correlationId))
            {
                break;
            }

            expiredTimers++;
            final int lastIndex = --size;
            final TimerEntry lastEntry = timers[lastIndex];
            timers[lastIndex] = null;

            if (0 != lastIndex)
            {
                shiftDown(timers, lastIndex, 0, lastEntry);
            }
        }

        return expiredTimers;
    }

    public void scheduleTimerForCorrelationId(final long correlationId, final long deadline)
    {
        final TimerEntry existingEntry = timerByCorrelationId.get(correlationId);
        if (null != existingEntry)
        {
            if (deadline < existingEntry.deadline)
            {
                existingEntry.deadline = deadline;
                shiftUp(timers, existingEntry.index, existingEntry);
            }
            else if (deadline > existingEntry.deadline)
            {
                existingEntry.deadline = deadline;
                shiftDown(timers, size, existingEntry.index, existingEntry);
            }
        }
        else
        {
            ensureCapacity(size + 1);

            final int index = size++;
            final TimerEntry entry = new TimerEntry(correlationId, deadline, index);

            timerByCorrelationId.put(correlationId, entry);
            shiftUp(timers, index, entry);
        }
    }

    public boolean cancelTimerByCorrelationId(final long correlationId)
    {
        final TimerEntry removed = timerByCorrelationId.remove(correlationId);
        if (null == removed)
        {
            return false;
        }

        final int lastIndex = --size;
        final TimerEntry last = timers[lastIndex];
        timers[lastIndex] = null;

        if (lastIndex != removed.index)
        {
            shiftDown(timers, lastIndex, removed.index, last);
        }

        return true;
    }

    public void snapshot(final TimerSnapshotTaker snapshotTaker)
    {
        final TimerEntry[] timers = this.timers;
        for (int i = 0, size = this.size; i < size; i++)
        {
            final TimerEntry entry = timers[i];
            snapshotTaker.snapshotTimer(entry.correlationId, entry.deadline);
        }
    }

    public void currentTime(final long now)
    {
    }

    private static void shiftUp(final TimerEntry[] timers, final int startIndex, final TimerEntry entry)
    {
        int index = startIndex;
        while (index > 0)
        {
            final int prevIndex = (index - 1) >>> 1;
            final TimerEntry prevEntry = timers[prevIndex];

            if (entry.deadline >= prevEntry.deadline)
            {
                break;
            }

            timers[index] = prevEntry;
            prevEntry.index = index;
            index = prevIndex;
        }

        timers[index] = entry;
        entry.index = index;
    }

    private static void shiftDown(
        final TimerEntry[] timers, final int size, final int startIndex, final TimerEntry entry)
    {
        final int half = size >>> 1;
        int index = startIndex;
        while (index < half)
        {
            int nextIndex = (index << 1) + 1;
            final int right = nextIndex + 1;
            TimerEntry nextEntry = timers[nextIndex];

            if (right < size && nextEntry.deadline > timers[right].deadline)
            {
                nextIndex = right;
                nextEntry = timers[nextIndex];
            }

            if (entry.deadline < nextEntry.deadline)
            {
                break;
            }

            timers[index] = nextEntry;
            nextEntry.index = index;
            index = nextIndex;
        }

        timers[index] = entry;
        entry.index = index;
    }

    private void ensureCapacity(final int requiredCapacity)
    {
        final int currentCapacity = timers.length;

        if (requiredCapacity > currentCapacity)
        {
            if (requiredCapacity > ArrayUtil.MAX_CAPACITY)
            {
                throw new IllegalStateException("max capacity reached: " + ArrayUtil.MAX_CAPACITY);
            }

            if (NO_TIMERS == timers)
            {
                timers = new TimerEntry[MIN_CAPACITY];
            }
            else
            {
                int newCapacity = currentCapacity + (currentCapacity >> 1);
                if (newCapacity < 0 || newCapacity > ArrayUtil.MAX_CAPACITY)
                {
                    newCapacity = ArrayUtil.MAX_CAPACITY;
                }
                timers = Arrays.copyOf(timers, newCapacity);
            }
        }
    }

    static final class TimerEntry
    {
        final long correlationId;
        long deadline;
        int index;

        private TimerEntry(final long correlationId, final long deadline, final int index)
        {
            this.correlationId = correlationId;
            this.deadline = deadline;
            this.index = index;
        }

        public String toString()
        {
            return "TimerEntry{" +
                "correlationId=" + correlationId +
                ", deadline=" + deadline +
                ", index=" + index +
                '}';
        }
    }
}
