package net.muxigame.outbreak;

import com.google.gson.*;
import static net.muxigame.minigames.TerminalUi.*;

final class OutbreakTerminalUi {
    private OutbreakTerminalUi(){}
    static JsonObject describe(JsonObject state){
        JsonObject ui=model(),own=null;for(JsonElement element:rows(state,"rooms"))if(flag(element.getAsJsonObject(),"mine")){own=element.getAsJsonObject();break;}
        var lobby=page(ui,"lobby","求援之路大厅");var create=section(lobby,"创建队伍","选择地图与难度后创建。地图准备和倒计时阶段允许队友加入，沿用原开局流程。");
        var maps=new JsonArray();for(JsonElement element:rows(state,"maps")){var map=element.getAsJsonObject();maps.add(option(text(map,"id"),text(map,"title")));}
        field(create,"map","地图",maps,maps.isEmpty()?"":text(maps.get(0).getAsJsonObject(),"value"));
        var difficulties=new JsonArray();String[] names={"简单","普通","困难","专家"};for(int i=0;i<names.length;i++)difficulties.add(option(Integer.toString(i),names[i]));field(create,"difficulty","难度",difficulties,"1");
        var action=action(create,"创建并准备房间","createConfigured","",own==null&&!maps.isEmpty());action.addProperty("jsonFields",true);action.addProperty("fields","map,difficulty");
        if(own!=null){var team=section(lobby,"我的队伍 · "+text(own,"id"),text(own,"title")+" · "+text(own,"phase")+" · "+number(own,"count")+"/4");for(JsonElement member:rows(own,"members"))card(team,member.getAsString(),"队伍成员");var leave=action(team,"退出并恢复原状态","leave","",true);leave.addProperty("safe",true);leave.addProperty("confirm","房主离开将结束队伍；恢复原背包与位置。");}
        if(own==null&&flag(state,"recovering")){var recovery=section(lobby,"恢复上次状态","上次游戏已中断，请先恢复原背包和位置。");var leave=action(recovery,"恢复并返回","leave","",true);leave.addProperty("safe",true);}
        var rooms=section(lobby,"可加入队伍","只允许准备和倒计时阶段加入；没有自动匹配队列。");
        for(JsonElement element:rows(state,"rooms")){var room=element.getAsJsonObject();if(flag(room,"mine"))continue;String phase=text(room,"phase");var row=card(rooms,text(room,"title")+" · "+text(room,"id"),phase+" · "+number(room,"count")+"/4");action(row,"加入队伍","join",text(room,"id"),own==null&&number(room,"count")<4&&(phase.equals("PREPARING")||phase.equals("COUNTDOWN")));}
        var shop=page(ui,"shop","求援之路商品");section(shop,"当前没有可兑换商品","此玩法未注册货币商店；场内拾取和补给沿用原规则。平台累计积分暂不支持兑换。");return ui;
    }
}
