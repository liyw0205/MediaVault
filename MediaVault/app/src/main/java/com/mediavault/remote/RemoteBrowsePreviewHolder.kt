package com.mediavault.remote

/** 设置里「浏览远程」未保存前，播放器用 preview id 读此处配置 */
object RemoteBrowsePreviewHolder {
    @Volatile
    var config: RemoteConfig? = null
}