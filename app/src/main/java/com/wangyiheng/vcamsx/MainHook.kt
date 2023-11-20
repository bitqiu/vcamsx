package com.wangyiheng.vcamsx

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CaptureRequest
import android.os.Handler
import android.view.Surface
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.exoplayer.ExoPlayer
import cn.dianbobo.dbb.util.HLog
import com.crossbowffs.remotepreferences.RemotePreferences
import com.google.gson.Gson
import com.wangyiheng.vcamsx.data.models.VideoStatues
import com.wangyiheng.vcamsx.utils.InfoManager
import de.robv.android.xposed.*
import de.robv.android.xposed.callbacks.XC_LoadPackage
import kotlinx.coroutines.*
import java.util.*


class MainHook : IXposedHookLoadPackage {
    private var videoStatus: VideoStatues? = null
    var infoManager : InfoManager?= null
    private lateinit var dataSourceFactory: DefaultDataSource.Factory
    private var player_exoplayer: ExoPlayer? = null
    private var context: Context? = null
    private var original_c2_preview_Surfcae: Surface? = null

    private var c2_virtual_surface: Surface? = null

    private var c2_state_callback_class: Class<*>? = null
    private var c2_state_callback: CameraDevice.StateCallback? = null
    private val gson = Gson()

    // Xposed模块中
    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        if(lpparam.packageName == "com.wangyiheng.vcamsx"){
            return
        }


        //获取context
        XposedHelpers.findAndHookMethod(
            "android.app.Instrumentation", lpparam.classLoader, "callApplicationOnCreate",
            Application::class.java, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam?) {
                    if (param!!.args[0] is Application) {

                        val application = param.args[0] as? Application ?: return
                        val applicationContext = application.applicationContext
                        if (context == applicationContext) return
                        try {
                            context = applicationContext
                            initStatus()
                        } catch (ee: Exception) {
                            HLog.d("VCAM", "$ee")
                        }
                    }
                }
            })

        XposedHelpers.findAndHookMethod(
            "android.hardware.camera2.CameraManager", lpparam.classLoader, "openCamera",
            String::class.java,
            CameraDevice.StateCallback::class.java,
            Handler::class.java, object : XC_MethodHook() {
                @Throws(Throwable::class)
                override fun afterHookedMethod(param: MethodHookParam) {
                    try {
                        if(param.args[1] == null){
                            return
                        }
                        if(param.args[1] == c2_state_callback){
                            return
                        }
                        c2_state_callback = param.args[1] as CameraDevice.StateCallback
                        c2_state_callback_class = param.args[1]?.javaClass
                        process_camera2_init(c2_state_callback_class as Class<Any>?,lpparam)
                    }catch (e:Exception){
                        HLog.d("android.hardware.camera2.CameraManager报错了", "openCamera")
                    }
                }
            })

    }


    fun initStatus(){
        infoManager = InfoManager(context!!)
        videoStatus = infoManager!!.getVideoStatus()
        HLog.d("info的数据：", infoManager!!.getVideoStatus().toString())
    }

    private fun process_camera2_init(c2StateCallbackClass: Class<Any>?, lpparam: XC_LoadPackage.LoadPackageParam) {
        XposedHelpers.findAndHookMethod(c2StateCallbackClass, "onOpened", CameraDevice::class.java, object : XC_MethodHook() {
            @Throws(Throwable::class)
            override fun beforeHookedMethod(param: MethodHookParam) {
                if(c2_virtual_surface!=null){
                    player_exoplayer!!.stop()
                    c2_virtual_surface!!.release()
                    c2_virtual_surface = null
                }
                original_c2_preview_Surfcae = null
            }
        })


        XposedHelpers.findAndHookMethod("android.hardware.camera2.CaptureRequest.Builder",
            lpparam.classLoader,
            "addTarget",
            android.view.Surface::class.java, object : XC_MethodHook() {
                @Throws(Throwable::class)
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (param.args[0] != null) {
                        if(param.args[0] == c2_virtual_surface)return
                        val surfaceInfo = param.args[0].toString()
                        HLog.d("surfaceInfo:",surfaceInfo)
                        if (!surfaceInfo.contains("Surface(name=null)")) {
                            if(original_c2_preview_Surfcae ==null ){
                                original_c2_preview_Surfcae = param.args[0] as Surface
                            }
                        }
                        process_camera2_exoplay_play()
                    }
                }
            })

        XposedHelpers.findAndHookMethod("android.hardware.camera2.CaptureRequest.Builder", lpparam.classLoader, "build",object :XC_MethodHook(){
            @Throws(Throwable::class)
            override fun beforeHookedMethod(param: MethodHookParam) {
                HLog.d(msg = "build构建成功")
            }
        })

        XposedHelpers.findAndHookMethod(c2StateCallbackClass, "onDisconnected",CameraDevice::class.java, object : XC_MethodHook() {
            @Throws(Throwable::class)
            override fun beforeHookedMethod(param: MethodHookParam) {
                HLog.d(msg="APP断开")
                if(c2_virtual_surface!=null){
                    player_exoplayer!!.stop()
                    c2_virtual_surface!!.release()
                    c2_virtual_surface = null
                }
                original_c2_preview_Surfcae = null
            }
        })
    }

    fun initPlayer(){
        player_exoplayer = ExoPlayer.Builder(context!!).build()
        dataSourceFactory = DefaultDataSource.Factory(context!!)
        player_exoplayer!!.repeatMode = Player.REPEAT_MODE_ALL
        if(videoStatus != null && videoStatus!!.volume){
            player_exoplayer!!.volume = 1f
        }else{
            player_exoplayer!!.volume = 0f
        }
        player_exoplayer!!.shuffleModeEnabled = true
        val mediaItem = MediaItem.fromUri("content://com.wangyiheng.vcamsx.videoprovider")

        HLog.d(msg = "视频设置成功")
        player_exoplayer!!.setMediaItem(mediaItem)
    }



    fun process_camera2_exoplay_play() {
        if (original_c2_preview_Surfcae != null) {
            initPlayer()
            HLog.d(msg = "构建完成开始播放")
            HLog.d("准备播放",videoStatus.toString())
            if(videoStatus != null && videoStatus!!.isVideoEnable){
                player_exoplayer!!.setVideoSurface(original_c2_preview_Surfcae)
                player_exoplayer!!.prepare()
                player_exoplayer!!.play()
            }
        }
    }
}