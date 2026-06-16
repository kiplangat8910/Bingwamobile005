package com.bingwa.mobile

internal const val SMS_TAGS = "Tags: {name}  {offer}  {amount}  {phone}"

internal const val DEFAULT_TPL_SUCCESS =
    "✓ Purchase Successful\n\nHi {name}, your {offer} data bundle was delivered successfully to {phone}.\n\nEnjoy your browsing and thank you for using Bingwa Sokoni."

internal const val DEFAULT_TPL_FAILED =
    "Hi {name}, we could not complete your {offer} bundle of KES {amount}. Please contact us for a refund or to retry. - Bingwa Sokoni"

internal const val DEFAULT_TPL_PENDING =
    "… Bingwa Sokoni Data Request Pending\n\nHi {name}, the selected number has already received a Bingwa Sokoni data offer today. Bingwa Sokoni bundles can only be purchased once per day per line.\n\nYour request has been scheduled and the {offer} bundle will be purchased automatically tomorrow once the line becomes eligible again."

internal const val DEFAULT_TPL_LIMIT_NOTICE =
    "Hi {name}, {offer} for {phone} can only be purchased once per day on the same line.\n\nReply 1 if you want to send another number for today. Reply 2 if you want us to dispatch it tomorrow morning when this same line becomes eligible again."

internal const val ACTION_TX_CREATED = "com.bingwa.mobile.TX_CREATED"

