package demo.org.neo4j.graphql

import graphql.language.ObjectTypeDefinition
import graphql.schema.idl.SchemaParser
import org.junit.Test
import org.neo4j.graphql.Translator
import org.neo4j.graphql.augmentedSchema
import kotlin.test.assertEquals

class AugmentationTest {
    val types = SchemaParser().parse("""
        type Person0 { name: String }
        type Person1 { name: String }
        type Person2 { name: String, age: [Int] }
        type Person3 { name: String!}
        type Person4 { id:ID!, name: String}
        type Person5 { id:ID!, movies:[Movie]}
        type Movie { id:ID! }
    """)

    @Test
    fun testCrud() {
        val ctx = Translator.Context(query = Translator.CRUDConfig(enabled = false), mutation = Translator.CRUDConfig(enabled = true, exclude = listOf("Person0")))
        assertEquals("",augmentedSchema(ctx, typeFor("Person0")).create)

        augmentedSchema(ctx, typeFor("Person1")).let {
            assertEquals("createPerson1(name:String) : Person1 ",it.create)
            assertEquals("",it.update)
            assertEquals("",it.delete)
            assertEquals("",it.query)
        }

        assertEquals("createPerson2(name:String, age:[Int]) : Person2 ", augmentedSchema(ctx, typeFor("Person2")).create)
        assertEquals("createPerson3(name:String!) : Person3 ", augmentedSchema(ctx, typeFor("Person3")).create)
        augmentedSchema(ctx, typeFor("Person4")).let {
            assertEquals("createPerson4(id:ID!, name:String) : Person4 ",it.create)
            assertEquals("updatePerson4(id:ID!, name:String) : Person4 ",it.update)
            assertEquals("deletePerson4(id:ID!) : Person4 ",it.delete)
            assertEquals("",it.query)
        }

    }
    @Test
    fun testQuery() {
        val ctx = Translator.Context(mutation = Translator.CRUDConfig(enabled = false), query = Translator.CRUDConfig(enabled = true, exclude = listOf("Person0")))
        assertEquals("",augmentedSchema(ctx, typeFor("Person0")).query)

        augmentedSchema(ctx, typeFor("Person1")).let {
            assertEquals("person1(name:String , _id: Int, filter:_Person1Filter, orderBy:_Person1Ordering, first:Int, offset:Int) : [Person1] ",it.query)
            assertEquals("",it.update)
            assertEquals("",it.delete)
            assertEquals("",it.create)
        }

        assertEquals("person2(name:String, age:[Int] , _id: Int, filter:_Person2Filter, orderBy:_Person2Ordering, first:Int, offset:Int) : [Person2] ", augmentedSchema(ctx, typeFor("Person2")).query)
        assertEquals("person3(name:String , _id: Int, filter:_Person3Filter, orderBy:_Person3Ordering, first:Int, offset:Int) : [Person3] ", augmentedSchema(ctx, typeFor("Person3")).query)
        assertEquals("person4(id:ID, name:String , _id: Int, filter:_Person4Filter, orderBy:_Person4Ordering, first:Int, offset:Int) : [Person4] ", augmentedSchema(ctx, typeFor("Person4")).query)
    }

    @Test
    fun testFilter() {
        val ctx = Translator.Context(mutation = Translator.CRUDConfig(enabled = false), query = Translator.CRUDConfig(enabled = true, exclude = listOf("Person0")))
        assertEquals("",augmentedSchema(ctx, typeFor("Person0")).filterType)

        augmentedSchema(ctx, typeFor("Person1")).let {
            assertEquals("input _Person1Filter { AND:[_Person1Filter!], OR:[_Person1Filter!], NOT:[_Person1Filter!], name:String, name_not:String, name_in:String, " +
                    "name_not_in:String, name_lt:String, name_lte:String, name_gt:String, name_gte:String, name_contains:String, name_not_contains:String, " +
                    "name_starts_with:String, name_not_starts_with:String, name_ends_with:String, name_not_ends_with:String } ",it.filterType)
            assertEquals("",it.update)
            assertEquals("",it.delete)
            assertEquals("",it.create)
        }

        assertEquals("input _Person2Filter { AND:[_Person2Filter!], OR:[_Person2Filter!], NOT:[_Person2Filter!], name:String, " +
                "name_not:String, name_in:String, name_not_in:String, name_lt:String, name_lte:String, name_gt:String, name_gte:String, " +
                "name_contains:String, name_not_contains:String, name_starts_with:String, name_not_starts_with:String, name_ends_with:String, " +
                "name_not_ends_with:String, age:[Int], age_not:[Int], age_in:[Int], age_not_in:[Int], age_lt:[Int], age_lte:[Int], " +
                "age_gt:[Int], age_gte:[Int] } ", augmentedSchema(ctx, typeFor("Person2")).filterType)
        assertEquals("input _Person3Filter { AND:[_Person3Filter!], OR:[_Person3Filter!], NOT:[_Person3Filter!], name:String, " +
                "name_not:String, name_in:String, name_not_in:String, name_lt:String, name_lte:String, name_gt:String, name_gte:String, " +
                "name_contains:String, name_not_contains:String, name_starts_with:String, name_not_starts_with:String, name_ends_with:String, " +
                "name_not_ends_with:String } ", augmentedSchema(ctx, typeFor("Person3")).filterType)
        assertEquals("input _Person4Filter { AND:[_Person4Filter!], OR:[_Person4Filter!], NOT:[_Person4Filter!], id:ID, id_not:ID, " +
                "id_in:ID, id_not_in:ID, id_lt:ID, id_lte:ID, id_gt:ID, id_gte:ID, id_contains:ID, id_not_contains:ID, " +
                "id_starts_with:ID, id_not_starts_with:ID, id_ends_with:ID, id_not_ends_with:ID, name:String, name_not:String, " +
                "name_in:String, name_not_in:String, name_lt:String, name_lte:String, name_gt:String, name_gte:String, name_contains:String, " +
                "name_not_contains:String, name_starts_with:String, name_not_starts_with:String, name_ends_with:String, " +
                "name_not_ends_with:String } ", augmentedSchema(ctx, typeFor("Person4")).filterType)
    }
    @Test
    fun testInputTypes() {
        val ctx = Translator.Context(mutation = Translator.CRUDConfig(enabled = false), query = Translator.CRUDConfig(enabled = true, exclude = listOf("Person0")))
        assertEquals("",augmentedSchema(ctx, typeFor("Person0")).inputType)

        augmentedSchema(ctx, typeFor("Person1")).let {
            assertEquals("input _Person1Input { name:String } ",it.inputType)
            assertEquals("",it.update)
            assertEquals("",it.delete)
            assertEquals("",it.create)
        }

        assertEquals("input _Person2Input { name:String, age:[Int] } ", augmentedSchema(ctx, typeFor("Person2")).inputType)
        assertEquals("input _Person3Input { name:String } ", augmentedSchema(ctx, typeFor("Person3")).inputType)
        assertEquals("input _Person4Input { id:ID, name:String } ", augmentedSchema(ctx, typeFor("Person4")).inputType)
    }

    @Test
    fun testOrderings() {
        val ctx = Translator.Context(mutation = Translator.CRUDConfig(enabled = false), query = Translator.CRUDConfig(enabled = true, exclude = listOf("Person0")))
        assertEquals("",augmentedSchema(ctx, typeFor("Person0")).ordering)

        augmentedSchema(ctx, typeFor("Person1")).let {
            assertEquals("enum _Person1Ordering { name_asc ,name_desc } ",it.ordering)
            assertEquals("",it.update)
            assertEquals("",it.delete)
            assertEquals("",it.create)
        }

        assertEquals("enum _Person2Ordering { name_asc ,name_desc,age_asc ,age_desc } ", augmentedSchema(ctx, typeFor("Person2")).ordering)
        assertEquals("enum _Person3Ordering { name_asc ,name_desc } ", augmentedSchema(ctx, typeFor("Person3")).ordering)
        assertEquals("enum _Person4Ordering { id_asc ,id_desc,name_asc ,name_desc } ", augmentedSchema(ctx, typeFor("Person4")).ordering)
    }

    @Test
    fun testMutations() {
        val ctx = Translator.Context(mutation = Translator.CRUDConfig(enabled = true, exclude = listOf("Person0","Person1","Person2","Person3")), query = Translator.CRUDConfig(enabled = false))
        assertEquals("",augmentedSchema(ctx, typeFor("Person0")).ordering)
        assertEquals("",augmentedSchema(ctx, typeFor("Person1")).filterType)
        assertEquals("",augmentedSchema(ctx, typeFor("Person2")).query)
        assertEquals("",augmentedSchema(ctx, typeFor("Person3")).inputType)

        augmentedSchema(ctx, typeFor("Person4")).let {
            assertEquals("",it.ordering)
            assertEquals("",it.filterType)
            assertEquals("createPerson4(id:ID!, name:String) : Person4 ",it.create)
            assertEquals("updatePerson4(id:ID!, name:String) : Person4 ",it.update)
            assertEquals("deletePerson4(id:ID!) : Person4 ",it.delete)
        }
    }

    @Test
    fun testMutationForRelations() {
        val ctx = Translator.Context(mutation = Translator.CRUDConfig(enabled = true, exclude = listOf("Person0","Person1","Person2","Person3", "Person4")), query = Translator.CRUDConfig(enabled = false))
        augmentedSchema(ctx, typeFor("Person5")).let {
            assertEquals("",it.ordering)
            assertEquals("",it.filterType)
            assertEquals("createPerson5(id:ID!) : Person5 ",it.create)
            assertEquals("updatePerson5(id:ID!) : Person5 ",it.update)
            assertEquals("deletePerson5(id:ID!) : Person5 ",it.delete)
        }
    }

    private fun typeFor(name: String) = types.getType(name).get() as ObjectTypeDefinition
}