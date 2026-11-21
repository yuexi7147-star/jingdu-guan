package com.jingdu.guan.data.db.entity

import androidx.room3.Entity
import androidx.room3.PrimaryKey

/**
 * 书签：常去的站，点了直接进网页。
 *
 * 和订阅源（[RssSourceEntity]）的区别要说清楚，两者不做成一件事：
 * - 订阅源有规则、能列内容、要追更新、会做有效性校验；
 * - 书签就是一个网址，随手存、随手删，点开是网页。
 *
 * 拿书签去填订阅源列表，会把「来源管理」撑成几百行杂项；
 * 反过来把订阅源塞进书签，则丢掉分类、分组、检测。
 */
@Entity(tableName = "bookmark")
data class BookmarkEntity(
    /** 网址本身当主键：同一个地址存两次没有意义 */
    @PrimaryKey val url: String,
    val title: String = "",
    val icon: String = "",
    /** 分组名，空 = 未分组 */
    val groupName: String = "",
    val sortOrder: Int = 0,
    val createdAt: Long = 0,
)
