package com.abdallah.cnnct.common.state

sealed interface ComponentState<out T> {
    object Loading : ComponentState<Nothing>
    data class Success<T>(val data: T) : ComponentState<T>
    data class Error(val what: String, val why: String, val actionText: String) : ComponentState<Nothing>
}
