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
package io.aeron;

import io.aeron.driver.MediaDriver;
import io.aeron.exceptions.RegistrationException;
import io.aeron.logbuffer.FragmentHandler;
import io.aeron.test.InterruptAfter;
import io.aeron.test.InterruptingTestCallback;
import io.aeron.test.Tests;
import io.aeron.test.driver.DistinctErrorLogTestWatcher;
import io.aeron.test.driver.MediaDriverTestWatcher;
import io.aeron.test.driver.TestMediaDriver;
import org.agrona.DirectBuffer;
import org.agrona.MutableDirectBuffer;
import org.agrona.collections.MutableLong;
import org.agrona.concurrent.UnsafeBuffer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.RegisterExtension;

import static java.util.Objects.requireNonNull;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

@ExtendWith(InterruptingTestCallback.class)
public class TimestampingSystemTest
{
    public static final long SENTINEL_VALUE = -1L;
    public static final String CHANNEL_WITH_MEDIA_TIMESTAMP =
        "aeron:udp?endpoint=localhost:0|media-rcv-ts-offset=reserved";
    public static final String CHANNEL_WITH_CHANNEL_TIMESTAMPS =
        "aeron:udp?endpoint=localhost:0|channel-rcv-ts-offset=0|channel-snd-ts-offset=8";

    @RegisterExtension
    public final MediaDriverTestWatcher watcher = new MediaDriverTestWatcher();

    @RegisterExtension
    public final DistinctErrorLogTestWatcher logWatcher = new DistinctErrorLogTestWatcher();

    private MediaDriver.Context context()
    {
        // TODO: temporary removal of SHARED to test
        return new MediaDriver.Context().dirDeleteOnStart(true);
    }

    @Test
    void shouldErrorOnMediaReceiveTimestampsInJavaDriver()
    {
        assumeTrue(TestMediaDriver.shouldRunJavaMediaDriver());

        try (TestMediaDriver driver = TestMediaDriver.launch(context(), watcher);
            Aeron aeron = Aeron.connect(new Aeron.Context().aeronDirectoryName(driver.aeronDirectoryName())))
        {
            assertThrows(
                RegistrationException.class,
                () -> aeron.addSubscription(CHANNEL_WITH_MEDIA_TIMESTAMP, 1000));
        }
    }

    @Test
    @InterruptAfter(10)
    @EnabledOnOs(OS.LINUX)
    void shouldSupportMediaReceiveTimestampsInCDriver()
    {
        assumeTrue(TestMediaDriver.shouldRunCMediaDriver());

        final DirectBuffer buffer = new UnsafeBuffer(new byte[64]);

        try (TestMediaDriver driver = TestMediaDriver.launch(context(), watcher);
            Aeron aeron = Aeron.connect(new Aeron.Context().aeronDirectoryName(driver.aeronDirectoryName())))
        {
            final Subscription sub = aeron.addSubscription(CHANNEL_WITH_MEDIA_TIMESTAMP, 1000);

            while (null == sub.resolvedEndpoint())
            {
                Tests.yieldingIdle("Failed to resolve endpoint");
            }

            final String uri = "aeron:udp?endpoint=" + sub.resolvedEndpoint();
            final Publication pub = aeron.addPublication(uri, 1000);

            Tests.awaitConnected(pub);

            while (0 > pub.offer(buffer, 0, buffer.capacity(), (termBuffer, termOffset, frameLength) -> SENTINEL_VALUE))
            {
                Tests.yieldingIdle("Failed to offer message");
            }

            final FragmentHandler fragmentHandler =
                (buffer1, offset, length, header) -> assertNotEquals(SENTINEL_VALUE, header .reservedValue());
            while (1 > sub.poll(fragmentHandler, 1))
            {
                Tests.yieldingIdle("Failed to receive message");
            }
        }
    }

    @Test
    @InterruptAfter(10)
    void shouldSupportSendReceiveTimestamps()
    {
        final MutableDirectBuffer buffer = new UnsafeBuffer(new byte[64]);

        final MediaDriver.Context context = context();
        final String aeronDirectoryName = context.aeronDirectoryName();

        try (TestMediaDriver driver = TestMediaDriver.launch(context, watcher);
            Aeron aeron = Aeron.connect(new Aeron.Context().aeronDirectoryName(driver.aeronDirectoryName())))
        {
            final Subscription sub = aeron.addSubscription(CHANNEL_WITH_CHANNEL_TIMESTAMPS, 1000);

            while (null == sub.resolvedEndpoint())
            {
                Tests.yieldingIdle("Failed to resolve endpoint");
            }

            final String uri = new ChannelUriStringBuilder(CHANNEL_WITH_CHANNEL_TIMESTAMPS)
                .endpoint(requireNonNull(sub.resolvedEndpoint()))
                .build();

            final Publication pub = aeron.addPublication(uri, 1000);

            Tests.awaitConnected(pub);

            buffer.putLong(0, SENTINEL_VALUE);
            buffer.putLong(8, SENTINEL_VALUE);

            while (0 > pub.offer(buffer, 0, buffer.capacity()))
            {
                Tests.yieldingIdle("Failed to offer message");
            }

            final MutableLong receiveTimestamp = new MutableLong(SENTINEL_VALUE);
            final MutableLong sendTimestamp = new MutableLong(SENTINEL_VALUE);
            final FragmentHandler fragmentHandler = (buffer1, offset, length, header) ->
            {
                receiveTimestamp.set(buffer1.getLong(offset));
                sendTimestamp.set(buffer1.getLong(offset + 8));
            };

            while (1 > sub.poll(fragmentHandler, 1))
            {
                Tests.yieldingIdle("Failed to receive message");
            }

            assertNotEquals(SENTINEL_VALUE, receiveTimestamp.longValue());
            assertNotEquals(SENTINEL_VALUE, sendTimestamp.longValue());
        }
        finally
        {
            logWatcher.captureErrors(aeronDirectoryName);
        }
    }

    @Test
    void shouldErrorIfSubscriptionConfigurationForTimestampsDoesNotMatch()
    {
        try (TestMediaDriver driver = TestMediaDriver.launch(context(), watcher);
            Aeron aeron = Aeron.connect(new Aeron.Context().aeronDirectoryName(driver.aeronDirectoryName())))
        {
            aeron.addSubscription("aeron:udp?endpoint=localhost:23436|channel-rcv-ts-offset=reserved", 1000);

            assertThrows(
                RegistrationException.class, () -> aeron.addSubscription("aeron:udp?endpoint=localhost:23436", 1000));
            assertThrows(
                RegistrationException.class,
                () -> aeron.addSubscription("aeron:udp?endpoint=localhost:23436|channel-rcv-ts-offset=8", 1000));
        }
    }

    @Test
    void shouldErrorIfPublicationConfigurationForTimestampsDoesNotMatch()
    {
        try (TestMediaDriver driver = TestMediaDriver.launch(context(), watcher);
            Aeron aeron = Aeron.connect(new Aeron.Context().aeronDirectoryName(driver.aeronDirectoryName())))
        {
            aeron.addPublication("aeron:udp?endpoint=localhost:23436|channel-snd-ts-offset=reserved", 1000);

            assertThrows(
                RegistrationException.class, () -> aeron.addPublication("aeron:udp?endpoint=localhost:23436", 1000));
            assertThrows(
                RegistrationException.class,
                () -> aeron.addPublication("aeron:udp?endpoint=localhost:23436|channel-snd-ts-offset=8", 1000));
        }
    }

    @Test
    @InterruptAfter(10)
    void shouldSupportChannelSendTimestampsOnMdc()
    {
        final MutableDirectBuffer buffer = new UnsafeBuffer(new byte[64]);

        final MediaDriver.Context context = context();
        final String aeronDirectoryName = context.aeronDirectoryName();

        try (TestMediaDriver driver = TestMediaDriver.launch(context, watcher);
            Aeron aeron = Aeron.connect(new Aeron.Context().aeronDirectoryName(driver.aeronDirectoryName())))
        {
            final Publication mdcPub = aeron.addPublication(
                "aeron:udp?control-mode=manual|channel-snd-ts-offset=0", 1000);

            final Subscription sub1 = aeron.addSubscription("aeron:udp?endpoint=localhost:23424", 1000);
            final Subscription sub2 = aeron.addSubscription("aeron:udp?endpoint=localhost:23425", 1000);
            mdcPub.addDestination("aeron:udp?endpoint=localhost:23424");
            mdcPub.addDestination("aeron:udp?endpoint=localhost:23425");

            while (!sub1.isConnected() || !sub2.isConnected())
            {
                Tests.yieldingIdle("Failed to connect");
            }

            buffer.putLong(0, SENTINEL_VALUE);

            while (0 > mdcPub.offer(buffer, 0, buffer.capacity()))
            {
                Tests.yieldingIdle("Failed to offer message");
            }

            final MutableLong sendTimestamp = new MutableLong(SENTINEL_VALUE);
            final FragmentHandler fragmentHandler =
                (buffer1, offset, length, header) -> sendTimestamp.set(buffer1 .getLong(offset));
            while (1 > sub1.poll(fragmentHandler, 1))
            {
                Tests.yieldingIdle("Failed to receive message");
            }

            assertNotEquals(SENTINEL_VALUE, sendTimestamp.longValue());

            while (1 > sub2.poll(fragmentHandler, 1))
            {
                Tests.yieldingIdle("Failed to receive message");
            }

            assertNotEquals(SENTINEL_VALUE, sendTimestamp.longValue());
        }
        finally
        {
            logWatcher.captureErrors(aeronDirectoryName);
        }
    }

    @Test
    @InterruptAfter(10)
    void shouldSupportReceiveTimestampsOnMds()
    {
        final MutableDirectBuffer buffer = new UnsafeBuffer(new byte[64]);

        final MediaDriver.Context context = context();
        final String aeronDirectoryName = context.aeronDirectoryName();

        try (TestMediaDriver driver = TestMediaDriver.launch(context, watcher);
            Aeron aeron = Aeron.connect(new Aeron.Context().aeronDirectoryName(driver.aeronDirectoryName())))
        {
            final Subscription mdsSub = aeron.addSubscription(
                "aeron:udp?control-mode=manual|channel-rcv-ts-offset=0", 1000);

            final Publication pub1 = aeron.addPublication("aeron:udp?endpoint=localhost:23424", 1000);
            final Publication pub2 = aeron.addPublication("aeron:udp?endpoint=localhost:23425", 1000);
            mdsSub.addDestination("aeron:udp?endpoint=localhost:23424");
            mdsSub.addDestination("aeron:udp?endpoint=localhost:23425");

            while (!pub1.isConnected() || !pub2.isConnected())
            {
                Tests.yieldingIdle("Failed to connect");
            }

            buffer.putLong(0, SENTINEL_VALUE);

            while (0 > pub1.offer(buffer, 0, buffer.capacity()))
            {
                Tests.yieldingIdle("Failed to offer message");
            }

            while (0 > pub2.offer(buffer, 0, buffer.capacity()))
            {
                Tests.yieldingIdle("Failed to offer message");
            }

            final MutableLong sendTimestamp = new MutableLong(SENTINEL_VALUE);
            final FragmentHandler fragmentHandler =
                (buffer1, offset, length, header) -> sendTimestamp.set(buffer1.getLong(offset));
            while (1 > mdsSub.poll(fragmentHandler, 1))
            {
                Tests.yieldingIdle("Failed to receive message");
            }

            assertNotEquals(SENTINEL_VALUE, sendTimestamp.longValue());

            while (1 > mdsSub.poll(fragmentHandler, 1))
            {
                Tests.yieldingIdle("Failed to receive message");
            }

            assertNotEquals(SENTINEL_VALUE, sendTimestamp.longValue());
        }
        finally
        {
            logWatcher.captureErrors(aeronDirectoryName);
        }
    }

    @Test
    @InterruptAfter(10)
    void shouldSupportReceiveTimestampsOnMergedMds()
    {
        final MutableDirectBuffer buffer = new UnsafeBuffer(new byte[64]);

        final MediaDriver.Context context = context();
        final String aeronDirectoryName = context.aeronDirectoryName();

        try (TestMediaDriver driver = TestMediaDriver.launch(context, watcher);
            Aeron aeron = Aeron.connect(new Aeron.Context().aeronDirectoryName(driver.aeronDirectoryName())))
        {
            final Subscription mdsSub = aeron.addSubscription(
                "aeron:udp?control-mode=manual|channel-rcv-ts-offset=0", 1000);

            final Publication pub1 = aeron.addExclusivePublication("aeron:udp?endpoint=localhost:23424", 1000);
            final String pub2Uri = new ChannelUriStringBuilder("aeron:udp?endpoint=localhost:23425")
                .initialPosition(0L, pub1.initialTermId(), pub1.termBufferLength())
                .sessionId(pub1.sessionId())
                .build();

            final Publication pub2 = aeron.addExclusivePublication(pub2Uri, 1000);
            mdsSub.addDestination("aeron:udp?endpoint=localhost:23424");
            mdsSub.addDestination("aeron:udp?endpoint=localhost:23425");

            while (!pub1.isConnected() || !pub2.isConnected())
            {
                Tests.yieldingIdle("Failed to connect");
            }

            final MutableLong sendTimestamp = new MutableLong(SENTINEL_VALUE);

            buffer.putLong(0, SENTINEL_VALUE);
            while (0 > pub1.offer(buffer, 0, buffer.capacity()))
            {
                Tests.yieldingIdle("Failed to offer message");
            }

            buffer.putLong(0, SENTINEL_VALUE);
            while (0 > pub2.offer(buffer, 0, buffer.capacity()))
            {
                Tests.yieldingIdle("Failed to offer message");
            }

            final FragmentHandler fragmentHandler =
                (buffer1, offset, length, header) -> sendTimestamp.set(buffer1.getLong(offset));
            while (1 > mdsSub.poll(fragmentHandler, 1))
            {
                Tests.yieldingIdle("Failed to receive message");
            }

            assertNotEquals(SENTINEL_VALUE, sendTimestamp.longValue());

            buffer.putLong(0, SENTINEL_VALUE);
            while (0 > pub2.offer(buffer, 0, buffer.capacity()))
            {
                Tests.yieldingIdle("Failed to offer message");
            }

            buffer.putLong(0, SENTINEL_VALUE);
            while (0 > pub1.offer(buffer, 0, buffer.capacity()))
            {
                Tests.yieldingIdle("Failed to offer message");
            }

            sendTimestamp.set(SENTINEL_VALUE);
            while (1 > mdsSub.poll(fragmentHandler, 1))
            {
                Tests.yieldingIdle("Failed to receive message");
            }

            assertNotEquals(SENTINEL_VALUE, sendTimestamp.longValue());
        }
        finally
        {
            logWatcher.captureErrors(aeronDirectoryName);
        }
    }
}
