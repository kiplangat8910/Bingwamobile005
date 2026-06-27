# Bingwa Mobile User Guide

## What Bingwa Mobile Does

Bingwa Mobile helps you send mobile data offers to customers using your phone. It is designed for operators who receive customer payments, dispatch bundles, and track activity from one app.

The app includes:

- A `Home` screen for status and activity
- A `Manual` screen for sending bundles
- A `Tokens` screen for usage balance
- A `Contacts` screen for customer names and numbers
- A `Settings` area for SIM, automation, relay mode, remote control, and diagnostics

## Before You Start

Before using the app, make sure:

- Your phone has an active SIM card
- You have airtime or the required network balance for bundle purchases
- You have enough tokens if your account uses token-based dispatch
- The app has permission to read SMS, send SMS, make phone calls, and read phone state
- Accessibility is enabled if you want advanced USSD automation

If your phone blocks background activity, also allow:

- Notifications
- Battery optimization exceptions for Bingwa Mobile
- Exact alarms on Android 12+

You can review all of these in `Settings > Setup Doctor`.

## First-Time Setup

Use this checklist when opening the app for the first time.

### 1. Open Setup Doctor

Go to `Settings > Setup Doctor` and review:

- Critical permissions
- Notifications
- Accessibility automation
- Battery protection
- Exact alarms
- SIM detection

Fix anything marked as needing attention before dispatching bundles.

### 2. Choose Your SIMs

Go to `Settings > SIM Settings` and choose:

- `USSD Execution SIM`: the SIM used for USSD bundle purchases
- `Customer Notification SIM`: the SIM used to send customer messages
- `Admin Reply SIM`: the SIM used to reply to admin SMS commands

If you only use one SIM, select that same SIM where needed.

### 3. Review Automation Settings

Go to `Settings > Automation Settings` and confirm how you want the app to behave. This area controls automation, retries, and auto-save behavior.

### 4. Set Up Contacts

Go to `Contacts` and build your customer directory:

- Add contacts manually
- Import customer names from M-PESA SMS
- Keep names clean so they are easy to search later

If your team uses automatic contact saving, review the auto-save options in `Settings` before going live.

### 5. Review Offers

Go to `Settings > Offers & USSD Codes` and confirm:

- The available bundle offers
- Offer names and prices
- The USSD codes used to execute each offer
- Which offers are enabled

Only enabled offers are available for dispatch.

### 6. Optional: Set Default SMS App

If your workflow depends on reading M-PESA messages, open `Settings` and set the app as the default SMS app if your device requires it.

## Main Screens

### Home

Use `Home` to quickly check:

- Whether automation is running
- Airtime or balance status
- Token or unlimited-plan status
- Recent activity
- Dispatch progress

This is the best screen for checking overall health before you begin work each day.

### Manual

Use `Manual` when you want to send a bundle directly to a customer.

Typical flow:

1. Enter the customer phone number
2. Search or confirm the customer name
3. Select an enabled offer
4. Review the status shown on screen
5. Start the dispatch

The app will show clear status feedback such as:

- Ready to dispatch
- Dispatch in progress
- Last dispatch completed successfully
- Last dispatch failed
- Request forwarded to relay

Use `Manual` for direct operator control when you do not want to rely only on automated triggers.

### Tokens

Use `Tokens` to monitor your usage balance.

Important notes:

- `1 token = 1 USSD call`
- Stored tokens do not expire
- Unlimited plans stay active until their timer ends

Check this screen before a busy sales period so you do not run out mid-operation.

### Contacts

Use `Contacts` to manage customer information.

You can:

- Save customer names and numbers
- Search existing contacts
- Import names from M-PESA SMS
- Clean up duplicate or inconsistent entries

Good contact data improves smart matching during manual dispatch.

### Settings

Use `Settings` to control how the app runs.

### History & Offers

- `Transaction History`: review payments, statuses, summaries, and cleanup tools
- `Offers & USSD Codes`: manage the bundles available for sale
- `Contacts`: maintain saved customer names and numbers

### Execution Setup

- `SIM Settings`: choose the SIMs used for USSD, customer SMS, and admin replies
- `Relay (Two-Phone Mode)`: connect a primary phone to a relay phone
- `Remote Control`: define the admin phone, SMS prefix, and security PIN

### Automation & Alerts

- `Automation Settings`: control automation, retries, and auto-save behavior
- `Customer Notifications`: customize success, pending, and failure messages
- `Admin Alerts`: receive alerts for low airtime, low tokens, and low battery

### Appearance & Support

- `Appearance`: change theme and colors
- `Setup Doctor`: check setup problems that can break automation
- `Accessibility`: open your phone's accessibility settings directly

## Daily Workflow

This is a practical routine for everyday use.

### Start of Day

1. Open `Home`
2. Confirm automation status
3. Check airtime and token balance
4. Open `Setup Doctor` if something looks wrong
5. Confirm the correct SIM is still selected

### During the Day

1. Receive a customer request or payment
2. Open `Manual`
3. Enter the customer phone number
4. Confirm the matching name from contacts or imported M-PESA data
5. Select the correct offer
6. Send the bundle
7. Watch the status until it completes or shows a failure

### End of Day

1. Open `Transaction History`
2. Review sent, pending, and failed items
3. Retry failed transactions if needed
4. Check low-balance alerts
5. Clean up contact or offer issues if you found any during the day

## Using Two-Phone Mode

Two-phone mode is useful when one phone acts as the main controller and another phone performs the actual execution.

Open `Settings > Relay (Two-Phone Mode)` to configure it.

You can set:

- Whether two-phone mode is enabled
- Whether this phone is the primary or relay phone
- The relay method, such as `SMS` or `HOTSPOT`
- Relay IP settings for hotspot mode
- A shared relay prefix
- An optional relay PIN
- Whether the relay phone sends results back by SMS

Use this mode only after both phones are configured correctly.

## Using Remote Control

Remote control lets an admin phone send SMS commands to Bingwa Mobile.

Open `Settings > Remote Control` and set:

- `Admin Phone`
- SMS command prefix
- Optional `Security PIN`

Remote admin commands are accepted only from the saved admin phone number.

Typical command format:

```text
<PREFIX> <COMMAND>
```

If a PIN is enabled:

```text
<PREFIX> <PIN> <COMMAND>
```

Examples of supported commands include:

- `BALANCE` or `B`
- `TOKENS` or `T`
- `STATUS` or `S`
- `BATTERY` or `BAT`
- `OFFERS` or `O`
- `ON <offer-id>`
- `OFF <offer-id>`
- `RETRY <tx-id>`
- `BUY <phone> <offer-id>`
- `BUYAMT <phone> <amount>`
- `P`
- `F`
- `BT <amount>`
- `PING`
- `HELP`

Use remote control carefully because it can trigger live dispatch actions.

## Notifications and Alerts

The app can send messages to both customers and the admin.

### Customer Notifications

In `Settings > Customer Notifications`, you can edit the message templates for:

- Success
- Pending
- Failure

### Admin Alerts

In `Settings > Admin Alerts`, you can enable alerts for:

- Low airtime
- Low tokens
- Low battery

These alerts help you react before dispatching stops.

## Troubleshooting

If the app is not working correctly, check these first.

### Dispatch does not start

- Confirm the correct `USSD Execution SIM` is selected
- Check that the phone has airtime or network balance
- Verify that the selected offer is enabled
- Make sure the app has phone and SMS permissions

### USSD automation fails

- Open `Settings > Setup Doctor`
- Enable accessibility for Bingwa Mobile
- Disable battery optimization for the app
- Allow exact alarms if your phone requires them

### M-PESA contacts do not appear

- Confirm the app can read SMS
- If needed, set Bingwa Mobile as the default SMS app
- Try importing from `Contacts` again

### Relay mode does not work

- Confirm both phones use the same relay prefix
- If a PIN is set, make sure it matches on both phones
- Check the paired phone number and connection method
- Recheck hotspot IP settings if using hotspot relay

### Remote SMS commands do not work

- Confirm the SMS came from the saved admin phone number
- Check the prefix format
- Include the PIN if one is enabled
- Make sure the app can send and receive SMS

## Good Operating Tips

- Test one offer first before enabling many offers
- Keep contact names consistent to improve matching
- Review failed transactions daily
- Watch token and airtime levels before peak hours
- Use `Setup Doctor` after changing phones, SIMs, or Android settings

## Summary

Bingwa Mobile is easiest to use when you follow this order:

1. Complete setup in `Setup Doctor`
2. Select the right SIMs
3. Confirm your offers
4. Build or import contacts
5. Use `Manual` for dispatch
6. Monitor `Home`, `Tokens`, and `Transaction History`
7. Use relay mode and remote control only after basic single-phone dispatch is working
