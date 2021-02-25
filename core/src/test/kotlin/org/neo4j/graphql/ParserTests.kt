package org.neo4j.graphql

import graphql.schema.idl.SchemaParser
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class ParserTests {
    @Test
    fun parseSelfReference() {
        val schemaParser = SchemaParser()
        val schema = """
            input Foo { foo:Foo, foo2: [Foo],foo3: [Foo!], bar: String, NOT: [Foo] }
        """.trimIndent()
        val typeDefinitionRegistry = schemaParser.parse(schema)
        assertEquals(true, typeDefinitionRegistry.getType("Foo").isPresent)

    }

    @Test
    fun parseFilter() {
        val schemaParser = SchemaParser()
        val schema = """
            input _MovieFilter { AND:[_MovieFilter!], OR:[_MovieFilter!], NOT:[_MovieFilter!], movieId:ID, movieId_not:ID, movieId_in:ID, movieId_not_in:ID, movieId_lt:ID, movieId_lte:ID, movieId_gt:ID, movieId_gte:ID, movieId_contains:ID, movieId_not_contains:ID, movieId_starts_with:ID, movieId_not_starts_with:ID, movieId_ends_with:ID, movieId_not_ends_with:ID, _id:String, _id_not:String, _id_in:String, _id_not_in:String, _id_lt:String, _id_lte:String, _id_gt:String, _id_gte:String, _id_contains:String, _id_not_contains:String, _id_starts_with:String, _id_not_starts_with:String, _id_ends_with:String, _id_not_ends_with:String, title:String, title_not:String, title_in:String, title_not_in:String, title_lt:String, title_lte:String, title_gt:String, title_gte:String, title_contains:String, title_not_contains:String, title_starts_with:String, title_not_starts_with:String, title_ends_with:String, title_not_ends_with:String, year:Int, year_not:Int, year_in:Int, year_not_in:Int, year_lt:Int, year_lte:Int, year_gt:Int, year_gte:Int, released:Int, released_not:Int, released_in:Int, released_not_in:Int, released_lt:Int, released_lte:Int, released_gt:Int, released_gte:Int, plot:String, plot_not:String, plot_in:String, plot_not_in:String, plot_lt:String, plot_lte:String, plot_gt:String, plot_gte:String, plot_contains:String, plot_not_contains:String, plot_starts_with:String, plot_not_starts_with:String, plot_ends_with:String, plot_not_ends_with:String, poster:String, poster_not:String, poster_in:String, poster_not_in:String, poster_lt:String, poster_lte:String, poster_gt:String, poster_gte:String, poster_contains:String, poster_not_contains:String, poster_starts_with:String, poster_not_starts_with:String, poster_ends_with:String, poster_not_ends_with:String, imdbRating:Float, imdbRating_not:Float, imdbRating_in:Float, imdbRating_not_in:Float, imdbRating_lt:Float, imdbRating_lte:Float, imdbRating_gt:Float, imdbRating_gte:Float, degree:Int, degree_not:Int, degree_in:Int, degree_not_in:Int, degree_lt:Int, degree_lte:Int, degree_gt:Int, degree_gte:Int, avgStars:Float, avgStars_not:Float, avgStars_in:Float, avgStars_not_in:Float, avgStars_lt:Float, avgStars_lte:Float, avgStars_gt:Float, avgStars_gte:Float, scaleRating:Float, scaleRating_not:Float, scaleRating_in:Float, scaleRating_not_in:Float, scaleRating_lt:Float, scaleRating_lte:Float, scaleRating_gt:Float, scaleRating_gte:Float, scaleRatingFloat:Float, scaleRatingFloat_not:Float, scaleRatingFloat_in:Float, scaleRatingFloat_not_in:Float, scaleRatingFloat_lt:Float, scaleRatingFloat_lte:Float, scaleRatingFloat_gt:Float, scaleRatingFloat_gte:Float }         """.trimIndent()
        val typeDefinitionRegistry = schemaParser.parse(schema)
        assertEquals(true, typeDefinitionRegistry.getType("_MovieFilter").isPresent)

    }

    @Test
    fun parseFilter2() {
        val schemaParser = SchemaParser()
        val schema = """
input _MovieFilter { AND:[_MovieFilter!], OR:[_MovieFilter!], NOT:[_MovieFilter!], movieId:ID, movieId_not:ID, movieId_in:ID, movieId_not_in:ID, movieId_lt:ID, movieId_lte:ID, movieId_gt:ID, movieId_gte:ID, movieId_contains:ID, movieId_not_contains:ID, movieId_starts_with:ID, movieId_not_starts_with:ID, movieId_ends_with:ID, movieId_not_ends_with:ID, _id:String, _id_not:String, _id_in:String, _id_not_in:String, _id_lt:String, _id_lte:String, _id_gt:String, _id_gte:String, _id_contains:String, _id_not_contains:String, _id_starts_with:String, _id_not_starts_with:String, _id_ends_with:String, _id_not_ends_with:String, title:String, title_not:String, title_in:String, title_not_in:String, title_lt:String, title_lte:String, title_gt:String, title_gte:String, title_contains:String, title_not_contains:String, title_starts_with:String, title_not_starts_with:String, title_ends_with:String, title_not_ends_with:String, year:Int, year_not:Int, year_in:Int, year_not_in:Int, year_lt:Int, year_lte:Int, year_gt:Int, year_gte:Int, released:Int, released_not:Int, released_in:Int, released_not_in:Int, released_lt:Int, released_lte:Int, released_gt:Int, released_gte:Int, plot:String, plot_not:String, plot_in:String, plot_not_in:String, plot_lt:String, plot_lte:String, plot_gt:String, plot_gte:String, plot_contains:String, plot_not_contains:String, plot_starts_with:String, plot_not_starts_with:String, plot_ends_with:String, plot_not_ends_with:String, poster:String, poster_not:String, poster_in:String, poster_not_in:String, poster_lt:String, poster_lte:String, poster_gt:String, poster_gte:String, poster_contains:String, poster_not_contains:String, poster_starts_with:String, poster_not_starts_with:String, poster_ends_with:String, poster_not_ends_with:String, imdbRating:Float, imdbRating_not:Float, imdbRating_in:Float, imdbRating_not_in:Float, imdbRating_lt:Float, imdbRating_lte:Float, imdbRating_gt:Float, imdbRating_gte:Float, degree:Int, degree_not:Int, degree_in:Int, degree_not_in:Int, degree_lt:Int, degree_lte:Int, degree_gt:Int, degree_gte:Int, avgStars:Float, avgStars_not:Float, avgStars_in:Float, avgStars_not_in:Float, avgStars_lt:Float, avgStars_lte:Float, avgStars_gt:Float, avgStars_gte:Float, scaleRating:Float, scaleRating_not:Float, scaleRating_in:Float, scaleRating_not_in:Float, scaleRating_lt:Float, scaleRating_lte:Float, scaleRating_gt:Float, scaleRating_gte:Float, scaleRatingFloat:Float, scaleRatingFloat_not:Float, scaleRatingFloat_in:Float, scaleRatingFloat_not_in:Float, scaleRatingFloat_lt:Float, scaleRatingFloat_lte:Float, scaleRatingFloat_gt:Float, scaleRatingFloat_gte:Float }
enum _MovieOrdering { movieId_asc ,movieId_desc,_id_asc ,_id_desc,title_asc ,title_desc,year_asc ,year_desc,released_asc ,released_desc,plot_asc ,plot_desc,poster_asc ,poster_desc,imdbRating_asc ,imdbRating_desc,degree_asc ,degree_desc,avgStars_asc ,avgStars_desc,scaleRating_asc ,scaleRating_desc,scaleRatingFloat_asc ,scaleRatingFloat_desc }
input _MovieInput { movieId:ID, _id:String, title:String, year:Int, released:Int, plot:String, poster:String, imdbRating:Float, degree:Int, avgStars:Float, scaleRating:Float, scaleRatingFloat:Float }
input _GenreFilter { AND:[_GenreFilter!], OR:[_GenreFilter!], NOT:[_GenreFilter!], _id:String, _id_not:String, _id_in:String, _id_not_in:String, _id_lt:String, _id_lte:String, _id_gt:String, _id_gte:String, _id_contains:String, _id_not_contains:String, _id_starts_with:String, _id_not_starts_with:String, _id_ends_with:String, _id_not_ends_with:String, name:String, name_not:String, name_in:String, name_not_in:String, name_lt:String, name_lte:String, name_gt:String, name_gte:String, name_contains:String, name_not_contains:String, name_starts_with:String, name_not_starts_with:String, name_ends_with:String, name_not_ends_with:String }
enum _GenreOrdering { _id_asc ,_id_desc,name_asc ,name_desc }
input _GenreInput { _id:String, name:String }
input _StateFilter { AND:[_StateFilter!], OR:[_StateFilter!], NOT:[_StateFilter!], name:String, name_not:String, name_in:String, name_not_in:String, name_lt:String, name_lte:String, name_gt:String, name_gte:String, name_contains:String, name_not_contains:String, name_starts_with:String, name_not_starts_with:String, name_ends_with:String, name_not_ends_with:String }
enum _StateOrdering { name_asc ,name_desc }
input _StateInput { name:String }
input _ActorFilter { AND:[_ActorFilter!], OR:[_ActorFilter!], NOT:[_ActorFilter!], userId:ID, userId_not:ID, userId_in:ID, userId_not_in:ID, userId_lt:ID, userId_lte:ID, userId_gt:ID, userId_gte:ID, userId_contains:ID, userId_not_contains:ID, userId_starts_with:ID, userId_not_starts_with:ID, userId_ends_with:ID, userId_not_ends_with:ID, name:String, name_not:String, name_in:String, name_not_in:String, name_lt:String, name_lte:String, name_gt:String, name_gte:String, name_contains:String, name_not_contains:String, name_starts_with:String, name_not_starts_with:String, name_ends_with:String, name_not_ends_with:String }
enum _ActorOrdering { userId_asc ,userId_desc,name_asc ,name_desc }
input _ActorInput { userId:ID, name:String }
input _UserFilter { AND:[_UserFilter!], OR:[_UserFilter!], NOT:[_UserFilter!], userId:ID, userId_not:ID, userId_in:ID, userId_not_in:ID, userId_lt:ID, userId_lte:ID, userId_gt:ID, userId_gte:ID, userId_contains:ID, userId_not_contains:ID, userId_starts_with:ID, userId_not_starts_with:ID, userId_ends_with:ID, userId_not_ends_with:ID, name:String, name_not:String, name_in:String, name_not_in:String, name_lt:String, name_lte:String, name_gt:String, name_gte:String, name_contains:String, name_not_contains:String, name_starts_with:String, name_not_starts_with:String, name_ends_with:String, name_not_ends_with:String }
enum _UserOrdering { userId_asc ,userId_desc,name_asc ,name_desc }
input _UserInput { userId:ID, name:String }
input _FriendOfFilter { AND:[_FriendOfFilter!], OR:[_FriendOfFilter!], NOT:[_FriendOfFilter!], since:Int, since_not:Int, since_in:Int, since_not_in:Int, since_lt:Int, since_lte:Int, since_gt:Int, since_gte:Int }
enum _FriendOfOrdering { since_asc ,since_desc }
input _FriendOfInput { since:Int }
input _RatedFilter { AND:[_RatedFilter!], OR:[_RatedFilter!], NOT:[_RatedFilter!], rating:Int, rating_not:Int, rating_in:Int, rating_not_in:Int, rating_lt:Int, rating_lte:Int, rating_gt:Int, rating_gte:Int }
enum _RatedOrdering { rating_asc ,rating_desc }
input _RatedInput { rating:Int }

        """
        val typeDefinitionRegistry = schemaParser.parse(schema)
        assertEquals(true, typeDefinitionRegistry.getType("_MovieFilter").isPresent)

    }
}
