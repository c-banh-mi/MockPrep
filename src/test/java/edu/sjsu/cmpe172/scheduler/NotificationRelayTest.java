package edu.sjsu.cmpe172.scheduler;

import edu.sjsu.cmpe172.scheduler.notification.NotificationGateway;
import edu.sjsu.cmpe172.scheduler.notification.NotificationRelay;
import edu.sjsu.cmpe172.scheduler.notification.NotificationRequest;
import edu.sjsu.cmpe172.scheduler.notification.NotificationSender;
import edu.sjsu.cmpe172.scheduler.notification.NotificationType;
import edu.sjsu.cmpe172.scheduler.notification.OutboxMessage;
import edu.sjsu.cmpe172.scheduler.notification.OutboxRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.util.List;

import static edu.sjsu.cmpe172.scheduler.NotificationSenderTest.OK;
import static edu.sjsu.cmpe172.scheduler.NotificationSenderTest.PROPS;
import static edu.sjsu.cmpe172.scheduler.NotificationSenderTest.down;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Ordering and give-up rules of the outbox relay, with a fake outbox and gateway. */
class NotificationRelayTest {

    private final OutboxRepository outbox = mock(OutboxRepository.class);
    private final NotificationGateway gateway = mock(NotificationGateway.class);
    private final NotificationRelay relay = new NotificationRelay(outbox,
            new NotificationSender(gateway, PROPS, new SimpleMeterRegistry()), PROPS);

    private static OutboxMessage row(long id, long appointmentId, NotificationType type, int attempts, boolean due) {
        return new OutboxMessage(id, new NotificationRequest(
                NotificationRequest.keyFor(appointmentId, type), "sam@example.com", type, "msg", appointmentId),
                attempts, due);
    }

    @Test
    void sendsRowsInWriteOrder() {
        OutboxMessage confirmed = row(1, 7, NotificationType.BOOKING_CONFIRMED, 0, true);
        OutboxMessage cancelled = row(2, 7, NotificationType.BOOKING_CANCELLED, 0, true);
        when(outbox.findPending(anyInt())).thenReturn(List.of(confirmed, cancelled));
        when(gateway.send(any())).thenReturn(OK);

        relay.drain();

        InOrder order = inOrder(gateway);
        order.verify(gateway).send(confirmed.request());
        order.verify(gateway).send(cancelled.request());
        verify(outbox).markSent(1, 1);
        verify(outbox).markSent(2, 1);
    }

    @Test
    void laterRowForTheSameAppointmentWaitsBehindAnEarlierUnsentRow() {
        // Appointment 7's confirmation is waiting for a retry (not due yet); its cancellation must not jump ahead.
        // Appointment 8 is unrelated and goes out normally.
        OutboxMessage confirmed7 = row(1, 7, NotificationType.BOOKING_CONFIRMED, 3, false);
        OutboxMessage cancelled7 = row(2, 7, NotificationType.BOOKING_CANCELLED, 0, true);
        OutboxMessage confirmed8 = row(3, 8, NotificationType.BOOKING_CONFIRMED, 0, true);
        when(outbox.findPending(anyInt())).thenReturn(List.of(confirmed7, cancelled7, confirmed8));
        when(gateway.send(any())).thenReturn(OK);

        relay.drain();

        verify(gateway, never()).send(cancelled7.request());
        verify(gateway, never()).send(confirmed7.request());
        verify(gateway).send(confirmed8.request());
    }

    @Test
    void serviceDownStopsTheRunAndReschedulesTheRow() {
        OutboxMessage first = row(1, 7, NotificationType.BOOKING_CONFIRMED, 0, true);
        OutboxMessage second = row(2, 8, NotificationType.BOOKING_CONFIRMED, 0, true);
        when(outbox.findPending(anyInt())).thenReturn(List.of(first, second));
        when(gateway.send(any())).thenThrow(down());

        relay.drain();

        verify(gateway, times(3)).send(first.request());    // one burst
        verify(gateway, never()).send(second.request());    // waits for the next run, in order
        verify(outbox).scheduleRetry(eq(1L), eq(3), eq(PROPS.redeliveryInterval()), anyString());
        verify(outbox, never()).markFailed(anyLong(), anyInt(), any());
    }

    @Test
    void givesUpAfterMaxRedeliveries() {
        OutboxMessage tired = row(1, 7, NotificationType.BOOKING_CONFIRMED, 9, true);
        when(outbox.findPending(anyInt())).thenReturn(List.of(tired));
        when(gateway.send(any())).thenThrow(down());

        relay.drain();

        verify(outbox).markFailed(eq(1L), eq(3), anyString());   // 9 + 3 >= 10
    }
}
