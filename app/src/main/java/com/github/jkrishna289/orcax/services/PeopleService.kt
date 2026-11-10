package com.github.jkrishna289.orcax.services

import android.content.Context
import com.github.jkrishna289.orcax.data.model.BaseItem
import com.github.jkrishna289.orcax.data.model.Person
import com.github.jkrishna289.orcax.data.model.oneCardPerPerson
import dagger.hilt.android.qualifiers.ApplicationContext
import org.jellyfin.sdk.api.client.ApiClient
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The cast and crew of an item, one card per person
 */
@Singleton
class PeopleService
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
        private val api: ApiClient,
    ) {
        fun getPeopleFor(item: BaseItem): List<Person> =
            item.data.people
                .orEmpty()
                .map { Person.fromDto(context, it, api) }
                .oneCardPerPerson()
    }
