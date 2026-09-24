package com.example.agent.rootpilot.ui

import com.example.agent.rootpilot.log.TraceEvent
import com.example.agent.rootpilot.log.TraceReason
import com.example.agent.rootpilot.log.TraceStage
import com.example.agent.rootpilot.log.TraceStatus

internal fun TraceStage.historyLabel(): String = when (this) {
    TraceStage.RUN -> "任务"
    TraceStage.SCREENSHOT -> "截图"
    TraceStage.MODEL -> "模型请求"
    TraceStage.PARSE -> "解析"
    TraceStage.POLICY -> "动作校验"
    TraceStage.APPROVAL -> "人工确认"
    TraceStage.EXECUTION -> "执行"
    TraceStage.SETTLE -> "等待页面"
    TraceStage.SERVICE -> "服务"
}

internal fun TraceEvent.historyLabel(): String = when (this) {
    TraceEvent.RUN_START -> "开始任务"
    TraceEvent.RUN_END -> "规划循环结束"
    TraceEvent.START -> "开始"
    TraceEvent.RESULT -> "结果"
    TraceEvent.PARSE_RETRY -> "重新解析"
    TraceEvent.WAITING -> "等待确认"
    TraceEvent.CONFIRMED -> "已确认"
    TraceEvent.REJECTED -> "未批准"
    TraceEvent.TODO_SAVED -> "待办已保存"
    TraceEvent.STOP_REQUESTED -> "请求停止（尚未结束）"
    TraceEvent.CONTROL -> "控制事件"
}

internal fun TraceStatus.historyLabel(): String = when (this) {
    TraceStatus.STARTED -> "开始"
    TraceStatus.WAITING -> "等待"
    TraceStatus.SUCCESS -> "成功"
    TraceStatus.FAILED -> "失败"
    TraceStatus.CANCELLED -> "取消"
    TraceStatus.REQUESTED -> "已请求"
}

internal fun TraceReason.historyLabel(): String = when (this) {
    TraceReason.NONE -> "无"
    TraceReason.INVALID_STEP_LIMIT -> "步骤上限无效"
    TraceReason.UPLOAD_NOT_ALLOWED -> "未授权上传截图"
    TraceReason.SCREENSHOT_FAILED -> "截图失败"
    TraceReason.UNCHANGED_SCREEN -> "画面持续未变化"
    TraceReason.MODEL_FAILED -> "模型请求失败"
    TraceReason.PARSE_FAILED -> "模型动作解析失败"
    TraceReason.MODEL_REPORTED_FAILURE -> "模型报告无法完成"
    TraceReason.DUPLICATE_TODO -> "拒绝重复保存待办"
    TraceReason.TODO_UNAVAILABLE -> "待办存储不可用"
    TraceReason.TODO_SAVE_FAILED -> "待办保存失败"
    TraceReason.REPEATED_ACTION -> "重复动作被阻止"
    TraceReason.POLICY_REJECTED -> "动作未通过策略校验"
    TraceReason.EXECUTION_FAILED -> "执行器返回失败"
    TraceReason.STEP_LIMIT -> "已达到步骤上限"
    TraceReason.USER_REJECTED -> "动作未获批准"
    TraceReason.CANCELLED -> "任务被取消"
    TraceReason.UNEXPECTED_ERROR -> "运行异常，详细内容不保存"
    TraceReason.ROOT_CHECK_STARTED -> "开始检查 Root"
    TraceReason.ROOT_CHECK_OK -> "Root 检查通过"
    TraceReason.ROOT_CHECK_FAILED -> "Root 检查失败"
    TraceReason.CAPTURE_STARTED -> "开始截图检查"
    TraceReason.CAPTURE_OK -> "截图检查通过"
    TraceReason.CAPTURE_FAILED -> "截图检查失败"
    TraceReason.SERVICE_ERROR -> "任务服务异常"
    TraceReason.SNAPSHOT_READ_FAILED -> "恢复记录读取失败"
    TraceReason.SNAPSHOT_WRITE_FAILED -> "恢复记录写入失败"
    TraceReason.SNAPSHOT_CLEAR_FAILED -> "恢复记录清除失败"
}

internal fun historyDuration(elapsedMs: Long): String {
    val seconds = elapsedMs.coerceAtLeast(0) / 1000
    return if (seconds < 60) "${seconds}秒" else "${seconds / 60}分${seconds % 60}秒"
}
