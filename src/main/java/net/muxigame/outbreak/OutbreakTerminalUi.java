package net.muxigame.outbreak;

import com.google.gson.*;
import static net.muxigame.minigames.TerminalUi.*;

final class OutbreakTerminalUi {
    private OutbreakTerminalUi(){}
    static JsonObject describe(JsonObject state){
        JsonObject ui=model(),own=null;for(JsonElement element:rows(state,"rooms"))if(flag(element.getAsJsonObject(),"mine")){own=element.getAsJsonObject();break;}
        var lobby=page(ui,"lobby","求援之路大厅");
        var create=section(lobby,"创建房间","选择模式、地图与难度。地图就绪后由房主开始，留出邀请与组队时间。");create.addProperty("role","create");
        var modes=new JsonArray();modes.add(option("CAMPAIGN","战役推进"));modes.add(option("SURVIVAL","生存防守"));field(create,"mode","模式",modes,"CAMPAIGN");
        var maps=new JsonArray();for(JsonElement element:rows(state,"maps")){var map=element.getAsJsonObject();maps.add(option(text(map,"id"),text(map,"title")));}
        field(create,"map","地图",maps,maps.isEmpty()?"":text(maps.get(0).getAsJsonObject(),"value"));
        var difficulties=new JsonArray();String[] names={"简单","普通","困难","专家"};for(int i=0;i<names.length;i++)difficulties.add(option(Integer.toString(i),names[i]));field(create,"difficulty","难度",difficulties,"1");
        var submit=action(create,"创建房间","createConfigured","",own==null&&!flag(state,"recovering")&&!maps.isEmpty());submit.addProperty("jsonFields",true);submit.addProperty("fields","map,difficulty,mode");
        if(own!=null){
            String phase=text(own,"phase");boolean host=text(own,"host").equals(text(state,"self"));boolean waiting=flag(own,"lobbyWaiting");
            String status=phase.equals("PREPARING")?"地图准备中":waiting?"等待房主开始":phase.equals("COUNTDOWN")?"开始倒计时":phase.equals("SAFE_ROOM")?"安全屋":phase.equals("RUNNING")?"战斗中":phase;
            var team=section(lobby,"我的队伍 · "+text(own,"id"),text(own,"title")+" · "+(text(own,"mode").equals("SURVIVAL")?"生存防守":"战役推进")+" · "+names[Math.max(0,Math.min(3,number(own,"difficulty")))]+" · "+status+" · "+number(own,"count")+"/4 · 章节 "+(number(own,"section")+1));team.addProperty("role","room");
            for(JsonElement member:rows(own,"members"))card(team,member.getAsString(),"房间成员");
            if(host&&waiting)action(team,"开始游戏","start","",phase.equals("COUNTDOWN"));
            var leave=action(team,"退出并恢复原状态","leave","",true);leave.addProperty("safe",true);leave.addProperty("confirm","离开当前队伍，恢复原背包与位置；房主退出会关闭房间。");
            if(host&&(phase.equals("PREPARING")||phase.equals("COUNTDOWN"))){var invite=section(lobby,"邀请队友","每队最多 4 人。队友可从本 APP 的房间列表加入。");invite.addProperty("role","invite");for(JsonElement element:rows(state,"players")){var player=element.getAsJsonObject();action(card(invite,text(player,"name"),"在线队友"),"邀请加入","invite",text(player,"id"),number(own,"count")<4);}}
        }
        if(own==null&&flag(state,"recovering")){var recovery=section(lobby,"恢复上次状态","上次游戏已中断，请先恢复原背包与位置。");recovery.addProperty("role","recovery");var leave=action(recovery,"恢复并返回","leave","",true);leave.addProperty("safe",true);}
        var rooms=section(lobby,"可加入房间","在准备与倒计时阶段可加入，游戏开始后不再接纳新成员。");rooms.addProperty("role","rooms");
        for(JsonElement element:rows(state,"rooms")){var room=element.getAsJsonObject();if(flag(room,"mine"))continue;String phase=text(room,"phase");var row=card(rooms,text(room,"title")+" · "+text(room,"id"),(flag(room,"invited")?"收到邀请 · ":"")+phase+" · "+number(room,"count")+"/4");action(row,"加入房间","join",text(room,"id"),own==null&&!flag(state,"recovering")&&number(room,"count")<4&&(phase.equals("PREPARING")||phase.equals("COUNTDOWN")));}
        var shop=page(ui,"shop","求援之路补给");section(shop,"场内补给","本玩法保留地图中的枪械、弹药与医疗补给，不扣兑换币或平台积分。APP 商店统一提供已有的枪战兑换商品；退出并恢复背包后可兑换。");
        return ui;
    }
}
