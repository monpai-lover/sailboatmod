package com.monpai.sailboatmod.nation.chat;
import com.monpai.sailboatmod.nation.data.NationSavedData;
import com.monpai.sailboatmod.nation.model.*;
import com.monpai.sailboatmod.nation.service.NationService;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class NationOfficeTitleTest {
    @Test void leaderUsesStoredTitle(){assertOffice("leader","皇帝");}
    @Test void officerUsesStoredTitle(){assertOffice("officer","海军大臣");}
    @Test void memberUsesStoredTitle(){assertOffice("member","国民");}
    @Test void customOfficeUsesStoredTitle(){assertOffice("admiral","舰队司令");}
    private void assertOffice(String id,String title){
        var data=new NationSavedData();var office=new NationOfficeRecord(id,title,10,Set.of());data.putOffice("test",office);
        assertEquals(title,NationService.resolveOfficeName(data,new NationMemberRecord(UUID.randomUUID(),"P","test",id,0)));
        assertEquals(office,NationOfficeRecord.load(office.save()));assertTrue(data.getOffice("test",id).permissions().isEmpty());
    }
}
