package com.monpai.sailboatmod.nation.chat;
import java.util.Optional;
/** Server-authored style metadata, not markup embedded in the player's signed chat body. */
public record ChatFlagBadge(String flagId,int primary,int secondary,boolean mirrored) {
    private static final String PREFIX="sailboatmod:flag:";
    public String encode(){return PREFIX+flagId+":"+Integer.toHexString(primary&0xffffff)+":"+Integer.toHexString(secondary&0xffffff)+":"+(mirrored?"1":"0");}
    public static Optional<ChatFlagBadge> parse(String value){
        if(value==null||value.length()>192||!value.startsWith(PREFIX))return Optional.empty();
        String[] parts=value.substring(PREFIX.length()).split(":",-1);
        if(parts.length!=4||!parts[0].matches("[a-z0-9_-]{1,128}")||!parts[1].matches("[0-9a-f]{1,6}")
                ||!parts[2].matches("[0-9a-f]{1,6}")||!(parts[3].equals("0")||parts[3].equals("1")))return Optional.empty();
        return Optional.of(new ChatFlagBadge(parts[0],Integer.parseInt(parts[1],16),Integer.parseInt(parts[2],16),parts[3].equals("1")));
    }
    public static boolean validTitle(String value,int max){
        return value!=null&&!value.isBlank()&&value.codePointCount(0,value.length())<=max
                &&value.codePoints().noneMatch(cp->Character.isISOControl(cp)||Character.getType(cp)==Character.FORMAT
                        ||cp==0xA7||cp==0xE000||cp=='['||cp==']');
    }
}
