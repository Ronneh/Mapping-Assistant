import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class T3dZoneParserTest {
    private static final String ACTOR = """
            Begin Actor Class=Brush Name=Detail
                CsgOper=CSG_Add
                PolyFlags=160
                Region=(Zone=LevelInfo'MyLevel.LevelInfo0',iLeaf=11,ZoneNumber=10)
                Location=(X=1,Y=2,Z=3)
            End Actor
            """;

    @Test void readsMetadataWithoutRewritingAnyText() {
        String source = ACTOR.replace("\n", "\r\n");
        var map = T3dZoneParser.parseMap(source);
        var actor = map.actors().get(0);
        assertEquals(source, actor.original());
        assertEquals("Detail", actor.name());
        assertEquals("Brush", actor.actorClass());
        assertEquals("CSG_Add", actor.csgOper());
        assertEquals(160L, actor.polyFlags());
        assertEquals(10, actor.zoneNumber());
        assertEquals(11, actor.iLeaf());
        assertEquals("(X=1,Y=2,Z=3)", actor.location());
        assertTrue(map.diagnostics().isEmpty());
    }

    @Test void conflictingMembershipIsNotAssignedToAnArbitraryZone() {
        for (String source : new String[] {
                ACTOR.replace("ZoneNumber=10", "ZoneNumber=10,ZoneNumber=12"),
                ACTOR.replace("End Actor", "    Region=(Zone=LevelInfo'MyLevel.LevelInfo0',ZoneNumber=12)\nEnd Actor") }) {
            assertNull(T3dZoneParser.parseMap(source).actors().get(0).zoneNumber());
        }
    }

    @Test void nestedPropertiesCannotOverrideActorMetadata() {
        String source = ACTOR.replace("End Actor", """
                    Begin Brush Name=Model1
                        PolyFlags=32
                        Region=(Zone=LavaZone'MyLevel.LavaZone0',ZoneNumber=15)
                        Begin PolyList
                            Begin Polygon Flags=32
                            End Polygon
                        End PolyList
                    End Brush
                End Actor""");
        var actor = T3dZoneParser.parseMap(source).actors().get(0);
        assertEquals(160L, actor.polyFlags());
        assertEquals(10, actor.zoneNumber());
        assertTrue(actor.defaultZone());
        assertEquals(source, actor.original());
    }

    @Test void commentsDoNotSupplyMetadata() {
        String source = "// Begin Actor Class=Trigger Name=NotAnActor\n" + ACTOR
                .replace("PolyFlags=160", "PolyFlags=160 // PolyFlags=0")
                .replace("End Actor", "// Region=(Zone=ZoneInfo'MyLevel.Zone',ZoneNumber=15)\nEnd Actor");
        var actors = T3dZoneParser.parseMap(source).actors();
        assertEquals(1, actors.size());
        assertEquals(160L, actors.get(0).polyFlags());
        assertEquals(10, actors.get(0).zoneNumber());
    }

    @Test void malformedStructureIsReportedWithoutLosingCompleteActors() {
        var result = T3dZoneParser.parseMap(ACTOR + "Begin Actor Class=Brush Name=Incomplete\n");
        assertEquals(1, result.actors().size());
        assertFalse(result.diagnostics().isEmpty());
    }
}
