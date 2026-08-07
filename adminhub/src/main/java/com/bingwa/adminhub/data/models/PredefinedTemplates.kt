package com.bingwa.adminhub.data.models

object PredefinedTemplates {
    val purchaseConfirmation = SmsTemplate(
        id = "template_purchase",
        name = "Purchase Confirmation",
        body = "Thank you for purchasing tokens on Bingwa Mobile! Your account has been credited. Enjoy!",
        category = TemplateCategory.PURCHASE
    )

    val tokenAdded = SmsTemplate(
        id = "template_token_added",
        name = "Tokens Added",
        body = "Your tokens have been added successfully. Thank you for trusting Bingwa Mobile.",
        category = TemplateCategory.ACTIVATION
    )

    val activationCode = SmsTemplate(
        id = "template_activation",
        name = "Activation Code",
        body = "Your activation code is: {CODE}. Enter it in the app to activate your tokens.",
        category = TemplateCategory.ACTIVATION
    )

    val clearNotice = SmsTemplate(
        id = "template_clear",
        name = "Clear Notice",
        body = "Your token balance has been cleared. Purchase new tokens to continue enjoying our services.",
        category = TemplateCategory.NOTIFICATION
    )

    val unlimitedActivated = SmsTemplate(
        id = "template_unlimited",
        name = "Unlimited Activated",
        body = "Unlimited usage has been activated for you. Enjoy unrestricted access to Bingwa Mobile services!",
        category = TemplateCategory.ACTIVATION
    )

    val giftNotice = SmsTemplate(
        id = "template_gift",
        name = "Gift Notice",
        body = "You have been gifted {AMOUNT} tokens! Redeem now and enjoy our services.",
        category = TemplateCategory.NOTIFICATION
    )

    val announcement = SmsTemplate(
        id = "template_announcement",
        name = "Announcement",
        body = "Important announcement from Bingwa Mobile: {MESSAGE}",
        category = TemplateCategory.CUSTOM
    )

    val all = listOf(
        purchaseConfirmation,
        tokenAdded,
        activationCode,
        clearNotice,
        unlimitedActivated,
        giftNotice,
        announcement
    )
}
