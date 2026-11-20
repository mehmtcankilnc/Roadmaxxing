<!--
DRAFT: written by the development team, not yet legally reviewed. The owner or a lawyer must review and
finalize this text before launch. It must stay in sync with privacy_tr.md and with what the app really does.
-->

# Privacy Policy

Last updated: 2026-11-20

This policy explains what Roadmaxxing does with your data. It describes the app as it works today. We tried to keep it short and plain.

## In short

- Without an account, your drives and the percentages we compute from them **stay on your phone**. Nothing is uploaded.
- An account is **optional**. If you create one, we store your email address, an account ID and the sign-in method, and the app **backs up** a summary of your drives and your settings to the cloud (details below). **Raw GPS points are never uploaded.**
- We **do not sell** your location data, and we do not share it for advertising.
- We do not use analytics or crash-reporting tools that send your data anywhere.

## What the app collects

**Location, only while a drive is recorded.** When the app thinks you may have started driving, it briefly checks your location to confirm. If it is a drive, it records GPS fixes (time, latitude, longitude, speed, direction and accuracy) until the drive ends. When you are not driving, high-accuracy GPS is off. This needs the location permission, and for automatic recording the "Allow all the time" option.

**Motion activity.** The app uses your phone's physical-activity sensor to notice that you may have started driving, so GPS does not have to run all the time. Only the activity type (for example "in a vehicle") is used.

**Drive results.** After a drive, the app matches your trace to roads and stores which roads you have driven, plus simple figures such as distance, duration and average and top speed.

**Account details (optional).** You can use the app without an account. If you choose to create one, you can sign up with your email address and a password, or sign in with Google. We then store your email address, an account ID and the sign-in provider (email or Google). If you sign in with Google, we receive the details Google shares for sign-in, such as your email address. We do not store your password in the app; passwords are handled by the authentication service.

## Where your data is processed and stored

Road matching and percentage calculation happen **on your device**. Recorded traces, drives and driven roads are stored in the app's local storage on your phone. Without an account, they are not sent to us or to anyone else. Android and iOS may also back up app data according to your own device settings, which is outside the app's control.

**Accounts.** Accounts are provided through **Firebase Authentication**, a service of Google LLC, which processes your account details on our behalf (as a processor). Signing in or out never changes the drives stored on your phone.

**Cloud backup, only with an account.** When, and only when, you have an account and are signed in, the app backs up the following to **Google Cloud Firestore** (part of Firebase, a service of Google LLC, which stores it on our behalf as a processor): for each drive, a summary (start and end time, distance, duration, average and top speed, and the length of new road it opened), a **simplified route** of the drive, and the **IDs of the road segments** you drove; plus your settings (distance unit, quiet hours and map layer choices), your privacy zones and the milestones you have reached. **Raw GPS points (your recorded traces) are never uploaded.** The purpose is to back up your progress and restore it on another phone. The data is stored under your account and is visible only to you, through your account; other users cannot read it. If you have no account, nothing is uploaded. When you sign in on a phone, drives already on that phone are uploaded to your account. If a different account signs in on a phone that already holds drives, the app asks you whether to add them to that account, replace them with that account's data, or cancel and sign out.

## Notifications

If you allow them, the app shows a notification while a drive is being recorded. The notification is created on your device.

## Maps and road data

The map is drawn with tiles loaded from **OpenFreeMap**. Like any web request, loading tiles reveals your IP address and the map area you look at to that service. We do not send your drives or your recorded locations to it. Road data comes from **OpenStreetMap** and is © OpenStreetMap contributors, available under the Open Database Licence (ODbL).

## Sharing cards

The app can create an image card (for example a drive summary). A card is created **only when you choose to share or save it**. What you do with the image afterwards, for example posting it in another app, is up to you and is covered by that app's rules. You can hide speed figures on the card before sharing.

## Selling and sharing

We do not sell your location data or any other personal data. We do not use your location for advertising.

## Keeping and deleting your data

Your drive data lives on your phone and, if you have an account, also in your cloud backup. You can export your drives as a single GPX file and share it through your phone's share sheet; nothing is uploaded. You can delete all your data on the phone in the app under Profile → Settings. If you are signed in, the app also offers to delete your cloud backup (ticked by default); if you leave it unticked, the cloud backup stays. Deletion is immediate and irreversible, and apart from a cloud backup you chose to keep, there is no backup we could restore it from. You can also delete your data by uninstalling the app. Note that roads you have driven are kept on your map until you delete your data.

If you have an account, you can delete it in the app under Profile. Deleting the account removes your account details (email address, account ID and sign-in provider) and **deletes your cloud backup** (drives, settings, privacy zones and milestones). If the cloud backup cannot be deleted, the account is not deleted and the app tells you. The app asks whether you also want to delete the drive data on your phone; that part is optional, and if you do not tick it, your local data stays on your phone. Signing out does not delete anything: your data stays on the phone and in the cloud backup.

## Children

Roadmaxxing is meant for people who are old enough to drive and is not directed at children. We do not knowingly collect data from children under 13 (or the age set by your local law).

## Your rights

Depending on where you live, laws such as the Turkish Personal Data Protection Law (KVKK) and the EU General Data Protection Regulation (GDPR) give you rights over your personal data. In short, you can ask whether your data is processed, ask for access, correction or deletion, object to processing, and complain to your data protection authority. Because most of your drive data is on your device, and you can delete your cloud backup and your account in the app, you can already exercise most of these rights yourself; deleting the app removes the local data. For any request, write to us using the contact below.

## Changes to this policy

We may update this policy as the app changes. The "last updated" date at the top shows the latest version. If a change affects how your data is used, we will tell you in the app before it takes effect.

## Contact

Questions or requests: [contact email]

Data controller: [company or owner name and address]
