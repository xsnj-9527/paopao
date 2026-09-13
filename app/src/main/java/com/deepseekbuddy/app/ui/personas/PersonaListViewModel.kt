package com.deepseekbuddy.app.ui.personas

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.deepseekbuddy.app.DeepSeekBuddyApp
import com.deepseekbuddy.app.data.ConversationItem
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

class PersonaListViewModel(application: Application) : AndroidViewModel(application) {

    private val container = (application as DeepSeekBuddyApp).container

    /** 会话列表（微信式），按最近聊天排序；null = 加载中（避免空态闪现） */
    val items: StateFlow<List<ConversationItem>?> = container.repository.observeConversationList()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
}
