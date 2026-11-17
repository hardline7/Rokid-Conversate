package app.conversate.glass.meeting

import android.content.Context

object DeviceLanguage {
    // Rokid's active system language, rather than the factory ro.product.locale.
    fun current(context: Context): String = L10n.normalize(context.resources.configuration.locales.get(0).toLanguageTag())
}
