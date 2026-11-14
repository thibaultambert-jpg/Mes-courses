package fr.mescourses.core.llm
enum class MsgRole { USER, ASSISTANT }
data class Msg(val role: MsgRole, val text: String)
