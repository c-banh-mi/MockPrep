package edu.sjsu.cmpe172.scheduler.notification;

import edu.sjsu.cmpe172.scheduler.model.AppointmentView;
import edu.sjsu.cmpe172.scheduler.repository.AppointmentRepository;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * Write side of the transactional outbox. Booking, cancelling and the reminder agent call this
 * INSIDE their own transaction ({@code MANDATORY}: calling it without one is a bug and fails),
 * so "appointment booked" and "confirmation queued" are one atomic database change.
 */
@Service
public class NotificationOutbox {

    private static final DateTimeFormatter WHEN =
            DateTimeFormatter.ofPattern("EEE, MMM d 'at' h:mm a", Locale.US);

    private final OutboxRepository outbox;
    private final AppointmentRepository appointments;
    private final ApplicationEventPublisher events;

    public NotificationOutbox(OutboxRepository outbox, AppointmentRepository appointments,
                              ApplicationEventPublisher events) {
        this.outbox = outbox;
        this.appointments = appointments;
        this.events = events;
    }

    /** Queues the standard confirmation / cancellation message for this appointment. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void enqueue(long appointmentId, NotificationType type) {
        AppointmentView appt = appointments.findViewById(appointmentId)
                .orElseThrow(() -> new IllegalStateException("Appointment " + appointmentId + " not found"));
        enqueue(appointmentId, type, message(appt, type));
    }

    /**
     * Queues a notification with a ready-made message (e.g. one the LLM wrote).
     *
     * @return false if this appointment already has a notification of this type queued or sent
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean enqueue(long appointmentId, NotificationType type, String message) {
        String email = appointments.findCustomerEmail(appointmentId)
                .orElseThrow(() -> new IllegalStateException("Appointment " + appointmentId + " not found"));
        boolean added = outbox.insert(new NotificationRequest(
                NotificationRequest.keyFor(appointmentId, type), email, type, message, appointmentId));
        if (added) {
            events.publishEvent(new OutboxWrittenEvent(appointmentId));
        }
        return added;
    }

    static String message(AppointmentView a, NotificationType type) {
        String what = a.serviceName() + " with " + a.providerName() + " on " + WHEN.format(a.startTime());
        return switch (type) {
            case BOOKING_CONFIRMED -> "Hi " + a.customerName() + ", your " + what
                    + " is confirmed (appointment #" + a.id() + ").";
            case BOOKING_CANCELLED -> "Hi " + a.customerName() + ", your " + what
                    + " has been cancelled (appointment #" + a.id() + ").";
            case APPOINTMENT_REMINDER -> "Hi " + a.customerName() + ", a reminder: your " + what
                    + " is coming up (appointment #" + a.id() + ").";
        };
    }
}
