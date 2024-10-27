# Foo Bar knock knock - Never miss your girlfriend's messages again ❤️

**An Android application that can listen for app notifications/calls and provide timely alerts**

**Principle:** Upon receiving a notification, upload it to Cloud Log (like [CLS](https://www.tencentcloud.com/products/cls)). The monitoring task regularly checks the time of the last received notification and the last read time. If a notification is determined to be unread, it will repeatedly trigger alerts for calls and messages.

We ensure that the listening tasks continue to operate through front-end services. If they stop unexpectedly, our heartbeat monitoring system will trigger alarms.
