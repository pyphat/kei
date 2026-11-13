package me.piyaphat.kei.ui.nav

import kotlinx.serialization.Serializable

@Serializable data object Library
@Serializable data object Queue
@Serializable data object Downloads
@Serializable data object Settings
@Serializable data class Series(val id: Long, val changeCover: Boolean = false)
@Serializable data class Reader(val chapterId: Long, val page: Int? = null)
@Serializable data object Upload
@Serializable data object Onboarding
