package com.jingdu.guan.data.db.dao

import androidx.room3.Dao
import androidx.room3.Query
import androidx.room3.Upsert
import com.jingdu.guan.data.db.entity.BookmarkEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface BookmarkDao {

    @Query("SELECT * FROM bookmark ORDER BY sortOrder ASC, createdAt DESC")
    fun observeAll(): Flow<List<BookmarkEntity>>

    @Query("SELECT * FROM bookmark WHERE url = :url")
    suspend fun get(url: String): BookmarkEntity?

    @Upsert
    suspend fun upsert(item: BookmarkEntity)

    @Query("DELETE FROM bookmark WHERE url = :url")
    suspend fun delete(url: String)

    @Query("UPDATE bookmark SET title = :title, icon = :icon WHERE url = :url")
    suspend fun updateMeta(url: String, title: String, icon: String)
}
