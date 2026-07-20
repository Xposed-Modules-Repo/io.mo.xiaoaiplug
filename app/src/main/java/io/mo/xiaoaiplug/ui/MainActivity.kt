package io.mo.xiaoaiplug.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import io.mo.xiaoaiplug.ui.nav.AppRoot
import io.mo.xiaoaiplug.ui.theme.AppTheme

class MainActivity : ComponentActivity() {

    // 跟各个 Composable 里 viewModel() 拿到的是同一个实例（都挂在 Activity 的 ViewModelStore 上）
    private val vm: ConfigViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        // 内容要能铺到系统栏底下，悬浮底栏的玻璃才有东西可透
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            AppTheme {
                AppRoot()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // 用户可能刚从系统设置里开完无障碍再切回来，回前台时重新查一遍状态
        vm.refreshStatus()
    }
}
