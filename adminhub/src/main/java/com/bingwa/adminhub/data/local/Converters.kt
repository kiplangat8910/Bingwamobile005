package com.bingwa.adminhub.data.local

import androidx.room.TypeConverter
import com.bingwa.adminhub.data.models.TemplateCategory

class Converters {
    @TypeConverter
    fun fromTemplateCategory(value: TemplateCategory): String = value.name

    @TypeConverter
    fun toTemplateCategory(value: String): TemplateCategory = TemplateCategory.valueOf(value)
}
