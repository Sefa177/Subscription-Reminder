# Subscription Reminder

A small Java desktop app for tracking recurring payments on Windows.

## Features
- Add, edit, and delete subscriptions.
- Track monthly or yearly billing dates and amounts in TRY, USD, or EUR.
- See upcoming and overdue payments.
- Receive Windows tray notifications while the app is running. Closing the window minimizes it to the tray; choose **Quit** from the tray menu to exit.
- Store subscription data locally in `%USERPROFILE%\.subscription-reminder\subscriptions.xml`.

## Run
Double-click `Run.bat` or run `java -jar SubscriptionReminder.jar`. Java 17 or newer is required.

## Build from source
```powershell
javac -encoding UTF-8 -d build SubscriptionReminder.java
jar --create --file SubscriptionReminder.jar --main-class SubscriptionReminder -C build .
```
