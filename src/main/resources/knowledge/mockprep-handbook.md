# MockPrep Handbook: Policies and FAQ

This handbook is the knowledge source for MockPrep's AI assistant (retrieval-augmented Q&A).
It is synthetic sample content written for this course project. Each "##" section is stored
as one or more chunks in the vector store.

## About MockPrep

MockPrep connects software engineers who are preparing for job interviews with experienced
mentors. Customers (job seekers) browse open time slots and book a session. Mentors (providers)
publish the time slots they are available. Every session is one-on-one and held over video call.
MockPrep is not a recruiting agency and does not guarantee job offers.

## Services and prices

MockPrep offers four services. Prices are per session, in US dollars.
- Resume Review: 30 minutes, $25. Line-by-line feedback on a software engineering resume.
- Mock Coding Interview: 60 minutes, $50. A LeetCode-style problem solved live, followed by feedback.
- Mock System Design: 60 minutes, $60. Design a large-scale system, then a debrief.
- Behavioral Interview Prep: 45 minutes, $35. STAR-method practice for behavioral questions.
A slot is always for one specific service; its length is that service's duration.

## Hours and time zone

All times on MockPrep are shown in Pacific Time (America/Los_Angeles).
Mentors can publish slots on any day of the week, including weekends.
Customer support answers messages Monday to Friday, 9:00 AM to 6:00 PM Pacific Time,
and replies within one business day. Support is closed on US federal holidays.

## How booking works

To book, choose an open slot and confirm. A slot can be booked by exactly one customer: if two
people try to book the same slot at the same moment, only one booking succeeds and the other
person is told the slot was just taken. You cannot hold two sessions that overlap in time.
Slots that have already started cannot be booked. After booking you receive a confirmation
message. You can add notes when booking, such as your target role or a link to your resume.

## Cancellation policy

You can cancel a booked session yourself at any time before it starts, from "My appointments"
or by asking the AI assistant. The slot then opens again for other customers, and you receive
a cancellation message. A session that has already started cannot be cancelled.
As a courtesy to mentors, please cancel at least 24 hours in advance.
Cancelling 24 hours or more before the start is free. Cancelling less than 24 hours before the
start is a late cancellation: the first late cancellation each month is free, after that 50%
of the session price is charged. Missing a session without cancelling (a no-show) is charged
in full. Mentors who cancel always refund the full price.

## Rescheduling

MockPrep has no separate reschedule button. To move a session, book the new time first, then
cancel the old session. Booking first makes sure you do not lose your place if the new time is
taken. The same cancellation policy applies to the session you cancel.

## Payments and refunds

The price is shown on every slot before you book. Refunds for free cancellations are issued to
the original payment method within 5 to 7 business days. MockPrep does not offer packages or
subscriptions at this time. Students with a valid university email can ask support for a 20%
student discount code.

## Reminders and notifications

MockPrep sends a confirmation message when you book and a cancellation message when a session
is cancelled. About 24 hours before a session, MockPrep's reminder agent sends you a
personalized reminder with preparation tips for your session type. Messages are sent to the
email address on your account. If the messaging service is temporarily down, messages are
queued and delivered, in order, as soon as it is back.

## How to prepare: Resume Review

Send your resume as a PDF link in the booking notes, at least one day before the session.
Tell the mentor which roles and level you are targeting (for example new-grad backend).
Keep it to one page if you have less than five years of experience. Quantify impact in each
bullet (for example "reduced p95 latency by 40%"). Come with two or three questions about
how to present your projects.

## How to prepare: Mock Coding Interview

Have a code editor or a shared coding pad ready and test your microphone and camera.
Pick one programming language you are fluent in. Practice explaining your thinking out loud:
restate the problem, ask about edge cases, give a brute-force idea, then improve it.
State time and space complexity at the end. Review arrays, hash maps, two pointers, BFS/DFS
and basic dynamic programming beforehand.

## How to prepare: Mock System Design

Have a whiteboard tool ready (for example Excalidraw). Practice a structure: clarify
requirements, estimate scale, define the API, sketch the high-level design, then go deep on
the data model, caching, partitioning and failure handling. Review load balancers, caches,
message queues, database replication and consistency trade-offs.

## How to prepare: Behavioral Interview Prep

Prepare five to six stories from your experience using the STAR method (Situation, Task,
Action, Result). Cover a conflict, a failure, a project you led, a tight deadline and a time
you helped a teammate. Keep each answer to about two minutes and end with what you learned.

## Session rules and etiquette

Join the video call on time; mentors wait up to 10 minutes, after which the session counts as
a no-show. Sessions end on time so the mentor can keep their next slot. Recording a session
requires the mentor's agreement. Be respectful; harassment leads to account suspension.

## Accounts and roles

There are three roles. Customers book and cancel their own sessions. Mentors (providers) add
and remove their own open slots and see who booked them. Admins operate the platform, for
example running the reminder agent on demand. A customer can only see and cancel their own
appointments. Passwords are stored as BCrypt hashes, never in plain text.

## Becoming a mentor

Mentors need at least three years of industry software engineering experience. Apply by
emailing support with your LinkedIn profile. Approved mentors publish their own availability
and receive 80% of each session price.

## Using the AI assistant

The MockPrep assistant can check availability, list your appointments, book and cancel
sessions, and answer questions about these policies. It always shows a summary and asks for
your confirmation before it books or cancels anything; nothing changes until you say yes.
It acts only as you, with the same permissions you have in the app. It answers policy
questions only from this handbook and says so when it does not know.

## Privacy

MockPrep stores your name, email, bookings and booking notes. Mentors see your name and notes
for sessions booked with them. MockPrep does not sell personal data. Messages to the AI
assistant are sent to Google's Gemini API to generate answers. You can ask support to delete
your account and data.
