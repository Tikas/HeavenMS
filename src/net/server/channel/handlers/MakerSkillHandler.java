/*
	This file is part of the OdinMS Maple Story Server
    Copyright (C) 2008 Patrick Huy <patrick.huy@frz.cc>
		       Matthias Butz <matze@odinms.de>
		       Jan Christian Meyer <vimes@odinms.de>

    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU Affero General Public License as
    published by the Free Software Foundation version 3 as published by
    the Free Software Foundation. You may not use, modify or distribute
    this program under any other version of the GNU Affero General Public
    License.

    This program is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU Affero General Public License for more details.

    You should have received a copy of the GNU Affero General Public License
    along with this program.  If not, see <http://www.gnu.org/licenses/>.
*/
package net.server.channel.handlers;

import java.util.LinkedList;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Map.Entry;

import constants.ItemConstants;
import client.MapleClient;
import client.inventory.Equip;
import client.inventory.Item;
import client.inventory.MapleInventoryType;
import constants.EquipType;
import constants.ServerConstants;
import java.text.DecimalFormat;
import java.text.NumberFormat;
import net.AbstractMaplePacketHandler;
import server.MapleInventoryManipulator;
import server.MapleItemInformationProvider;
import server.MakerItemFactory;
import server.MakerItemFactory.MakerItemCreateEntry;
import tools.FilePrinter;
import tools.Pair;
import tools.MaplePacketCreator;
import tools.data.input.SeekableLittleEndianAccessor;

/**
 *
 * @author Jay Estrella, Ronan
 */
public final class MakerSkillHandler extends AbstractMaplePacketHandler {
    private static MapleItemInformationProvider ii = MapleItemInformationProvider.getInstance();
    
    @Override
    public final void handlePacket(SeekableLittleEndianAccessor slea, MapleClient c) {
        int type = slea.readInt();
        int toCreate = slea.readInt();
        int toDisassemble = -1, pos = -1;
        boolean makerSucceeded = true;
        
        MakerItemCreateEntry recipe;
        Map<Integer, Short> reagentids = new LinkedHashMap<>();
        int stimulantid = -1;
        
        if(type == 3) {    // building monster crystal
            int fromLeftover = toCreate;
            toCreate = ii.getMakerCrystalFromLeftover(toCreate);
            if(toCreate == -1) {
                c.announce(MaplePacketCreator.serverNotice(1, ii.getName(toCreate) + " is unavailable for Monster Crystal conversion."));
                return;
            }
            
            recipe = MakerItemFactory.generateLeftoverCrystalEntry(fromLeftover);
        } else if(type == 4) {  // disassembling
            slea.readInt(); // 1... probably inventory type
            pos = slea.readInt();
            
            Item it = c.getPlayer().getInventory(MapleInventoryType.EQUIP).getItem((short) pos);
            if(it != null && it.getItemId() == toCreate) {
                Pair<Integer, Integer> p;
                        
                if((p = generateDisassemblyInfo(toCreate)) != null) {
                    recipe = MakerItemFactory.generateDisassemblyCrystalEntry(p.getLeft(), p.getRight());
                    toDisassemble = toCreate;
                    toCreate = ii.getMakerCrystalFromEquip(toCreate);
                } else {
                    c.announce(MaplePacketCreator.serverNotice(1, ii.getName(toCreate) + " is unavailable for Monster Crystal disassembly."));
                    return;
                }
            } else {
                c.announce(MaplePacketCreator.serverNotice(1, "An unknown error occurred when trying to apply that item for disassembly."));
                return;
            }
        } else {
            if(ItemConstants.isEquipment(toCreate)) {   // only equips uses stimulant and reagents
                if(slea.readByte() != 0) {  // stimulant
                    stimulantid = getMakerStimulant(toCreate);
                    if(!c.getAbstractPlayerInteraction().haveItem(stimulantid)) {
                        stimulantid = -1;
                    }
                }

                int reagents = Math.min(slea.readInt(), getMakerReagentSlots(toCreate));
                for(int i = 0; i < reagents; i++) {  // crystals
                    int reagentid = slea.readInt();
                    if(ItemConstants.isMakerReagent(reagentid)) {
                        Short r = reagentids.get(reagentid);
                        if(r == null) {
                            reagentids.put(reagentid, (short) 1);
                        } else {
                            reagentids.put(reagentid, (short) (r + 1));
                        }
                    }
                }

                List<Pair<Integer, Short>> toUpdate = new LinkedList<>();
                for(Entry<Integer, Short> r : reagentids.entrySet()) {
                    int qty = c.getAbstractPlayerInteraction().getItemQuantity(r.getKey());

                    if(qty < r.getValue()) {
                        toUpdate.add(new Pair<>(r.getKey(), (short) qty));
                    }
                }

                // remove those not present on player inventory
                if(!toUpdate.isEmpty()) {
                    for(Pair<Integer, Short> r : toUpdate) {
                        if(r.getRight() > 0) {
                            reagentids.put(r.getLeft(), r.getRight());
                        } else {
                            reagentids.remove(r.getLeft());
                        }
                    }
                }

                if(!reagentids.isEmpty()) removeOddMakerReagents(toCreate, reagentids);
            }
            
            recipe = MakerItemFactory.getItemCreateEntry(toCreate, stimulantid, reagentids);
        }
        
        short createStatus = getCreateStatus(c, recipe);
        
        switch(createStatus) {
            case -1:// non-available for Maker itemid has been tried to forge
                FilePrinter.printError(FilePrinter.EXPLOITS, "Player " + c.getPlayer().getName() + " tried to craft itemid " + toCreate + " using the Maker skill.");
                c.announce(MaplePacketCreator.serverNotice(1, "The requested item could not be crafted on this operation."));
                break;
            
            case 1: // no items
                c.announce(MaplePacketCreator.serverNotice(1, "You don't have all required items in your inventory to make " + recipe.getRewardAmount() + " " + ii.getName(toCreate) + "."));
                break;
                
            case 2: // no meso
                NumberFormat nf = new DecimalFormat("#,###,###,###");
                c.announce(MaplePacketCreator.serverNotice(1, "You don't have enough mesos (" + nf.format(recipe.getCost()) + ") to complete this operation."));
                break;
                    
            case 3: // no req level
                c.announce(MaplePacketCreator.serverNotice(1, "You don't have enough level to complete this operation."));
                break;
                
            case 4: // no req skill level
                c.announce(MaplePacketCreator.serverNotice(1, "You don't have enough Maker level to complete this operation."));
                break;
                
            default:
                if (MapleInventoryManipulator.checkSpace(c, toCreate, (short) recipe.getRewardAmount(), "")) {
                    for (Pair<Integer, Integer> p : recipe.getReqItems()) {
                        c.getAbstractPlayerInteraction().gainItem(p.getLeft(), (short) -p.getRight());
                    }
                    
                    if(toDisassemble != -1) {
                        MapleInventoryManipulator.removeFromSlot(c, MapleInventoryType.EQUIP, (short) pos, (short) 1, false);
                        c.announce(MaplePacketCreator.getShowItemGain(toDisassemble, (short) -1, true));
                    }
                    
                    int cost = recipe.getCost();
                    if(stimulantid == -1 && reagentids.isEmpty()) {
                        if(cost > 0) c.getPlayer().gainMeso(-cost);
                        
                        c.getPlayer().setCS(true);
                        c.getAbstractPlayerInteraction().gainItem(toCreate, (short) recipe.getRewardAmount());
                        c.getPlayer().setCS(false);
                    } else {
                        if(stimulantid != -1) c.getAbstractPlayerInteraction().gainItem(stimulantid, (short) -1);
                        if(!reagentids.isEmpty()) {
                            for(Entry<Integer, Short> r : reagentids.entrySet()) {
                                c.getAbstractPlayerInteraction().gainItem(r.getKey(), (short) (-1 * r.getValue()));
                            }
                        }
                        
                        if(cost > 0) c.getPlayer().gainMeso(-cost);
                        makerSucceeded = addBoostedMakerItem(c, toCreate, stimulantid, reagentids);
                    }
                    
                    if(makerSucceeded) c.announce(MaplePacketCreator.serverNotice(1, "You have successfully created " + recipe.getRewardAmount() + " " + ii.getName(toCreate) + "."));
                    else c.getPlayer().dropMessage(5, "The Maker skill lights up, but the skill winds up as if nothing happened.");
                    
                    c.announce(MaplePacketCreator.showMakerEffect(makerSucceeded));
                    c.getPlayer().getMap().broadcastMessage(c.getPlayer(), MaplePacketCreator.showForeignMakerEffect(c.getPlayer().getId(), makerSucceeded), false);
                    
                    if(toCreate == 4260003 && c.getPlayer().getQuestStatus(6033) == 1) {
                        c.getAbstractPlayerInteraction().setQuestProgress(6033, 1);
                    }
                } else {
                    c.announce(MaplePacketCreator.serverNotice(1, "Your inventory is full."));
                }
        }
    }

    // checks and prevents hackers from PE'ing Maker operations with invalid operations
    private static void removeOddMakerReagents(int toCreate, Map<Integer, Short> reagentids) {
        Map<Integer, Integer> reagentType = new LinkedHashMap<>();
        List<Integer> toRemove = new LinkedList<>();
        
        boolean isWeapon = ItemConstants.isWeapon(toCreate);
        
        for(Entry<Integer, Short> r : reagentids.entrySet()) {
            int curRid = r.getKey();
            int type = r.getKey() / 100;
            
            if(type < 42502 && !isWeapon) {     // only weapons should gain w.att/m.att from these.
                toRemove.add(curRid);
            } else {
                Integer tableRid = reagentType.get(type);
                
                if(tableRid != null) {
                    if(tableRid < curRid) {
                        toRemove.add(tableRid);
                        reagentType.put(type, curRid);
                    } else {
                        toRemove.add(curRid);
                    }
                } else {
                    reagentType.put(type, curRid);
                }
            }
        }
        
        // removing less effective gems of repeated type
        for(Integer i : toRemove) {
            reagentids.remove(i);
        }
        
        // only quantity 1 of each gem will be accepted by the Maker skill
        for(Integer i : reagentids.keySet()) {
            reagentids.put(i, (short) 1);
        }
    }
    
    private static int getMakerReagentSlots(int itemId) {
        try {
            int eqpLevel = ii.getEquipStats(itemId).get("reqLevel");
            
            if(eqpLevel < 78) {
                return 1;
            } else if(eqpLevel >= 78 && eqpLevel < 108) {
                return 2;
            } else {
                return 3;
            }
        } catch(NullPointerException npe) {
            return 0;
        }
    }
    
    private static int getMakerStimulant(int itemId) {
        EquipType et = EquipType.getEquipTypeById(itemId);
        
        switch(et) {
            case GLOVES:
                return 4130000;
            
            case SHOES:
                return 4130001;
            
            case SWORD:
                return 4130002;
            
            case AXE:
                return 4130003;
                
            case MACE:
                return 4130004;
            
            case SWORD_2H:
                return 4130005;
            
            case AXE_2H:
                return 4130006;
            
            case MACE_2H:
                return 4130007;
            
            case SPEAR:
                return 4130008;
            
            case POLEARM:
                return 4130009;
            
            case WAND:
                return 4130010;
                
            case STAFF:
                return 4130011;
                    
            case BOW:
                return 4130012;
                        
            case CROSSBOW:
                return 4130013;
                            
            case DAGGER:
                return 4130014;
                                
            case CLAW:
                return 4130015;
                                    
            case KNUCKLER:
                return 4130016;
                                        
            case PISTOL:
                return 4130017;
            
            case CAP:
                return 4130018;
                
            case COAT:
                return 4130019;
                
            case PANTS:
                return 4130020;
                
            case LONGCOAT:
                return 4130021;
                
            case SHIELD:
                return 4130022;
                
            default:
                return -1;
        }
    }
    
    private static Pair<Integer, Integer> generateDisassemblyInfo(int itemId) {
        int recvFee = ii.getMakerDisassembledFee(itemId);
        if(recvFee > -1) {
            int recvQty = ii.getMakerDisassembledQuantity(itemId);
            if(recvQty > 0) {
                return new Pair<>(recvFee, recvQty);
            }
        }
        
        return null;
    }
    
    private static short getCreateStatus(MapleClient c, MakerItemCreateEntry recipe) {
        if(recipe == null) {
            return -1;
        }
        
        if(!hasItems(c, recipe)) {
            return 1;
        }
        
        if(c.getPlayer().getMeso() < recipe.getCost()) {
            return 2;
        }
        
        if(c.getPlayer().getLevel() < recipe.getReqLevel()) {
            return 3;
        }
        
        if(c.getPlayer().getSkillLevel((c.getPlayer().getJob().getId() / 1000) * 10000000 + 1007) < recipe.getReqSkillLevel()) {
            return 4;
        }
        
        return 0;
    }

    private static boolean hasItems(MapleClient c, MakerItemCreateEntry recipe) {
        for (Pair<Integer, Integer> p : recipe.getReqItems()) {
            int itemId = p.getLeft();
            if (c.getPlayer().getInventory(ItemConstants.getInventoryType(itemId)).countById(itemId) < p.getRight()) {
                return false;
            }
        }
        return true;
    }
    
    private static boolean addBoostedMakerItem(MapleClient c, int itemid, int stimulantid, Map<Integer, Short> reagentids) {
        if(stimulantid != -1 && !ii.rollSuccessChance(90.0)) {
            return false;
        }
        
        Item item = ii.getEquipById(itemid);
        if(item == null) return false;

        Equip eqp = (Equip)item;
        if(ItemConstants.isAccessory(item.getItemId()) && eqp.getUpgradeSlots() <= 0) eqp.setUpgradeSlots(3);

        if(ServerConstants.USE_ENHANCED_CRAFTING == true) {
            if(!(c.getPlayer().isGM() && ServerConstants.USE_PERFECT_GM_SCROLL)) {
                eqp.setUpgradeSlots((byte)(eqp.getUpgradeSlots() + 1));
            }
            item = MapleItemInformationProvider.getInstance().scrollEquipWithId(eqp, 2049100, true, 0, c.getPlayer().isGM());
        }
        
        if(!reagentids.isEmpty()) {
            Map<String, Integer> stats = new LinkedHashMap<>();
            List<Short> randOption = new LinkedList<>();
            List<Short> randStat = new LinkedList<>();
            
            for(Entry<Integer, Short> r : reagentids.entrySet()) {
                Pair<String, Integer> reagentBuff = ii.getMakerReagentStatUpgrade(r.getKey());
                
                if(reagentBuff != null) {
                    String s = reagentBuff.getLeft();
                    
                    if(s.substring(0, 4).contains("rand")) {
                        if(s.substring(4).equals("Stat")) {
                            randStat.add((short) (reagentBuff.getRight() * r.getValue()));
                        } else {
                            randOption.add((short) (reagentBuff.getRight() * r.getValue()));
                        }
                    } else {
                        String stat = s.substring(3);
                        
                        if(!stat.equals("ReqLevel")) {    // improve req level... really?
                            switch (stat) {
                                case "MaxHP":
                                    stat = "MHP";
                                    break;
                                    
                                case "MaxMP":
                                    stat = "MMP";
                                    break;
                            }
                            
                            Integer d = stats.get(stat);
                            if(d == null) {
                                stats.put(stat, reagentBuff.getRight() * r.getValue());
                            } else {
                                stats.put(stat, d + (reagentBuff.getRight() * r.getValue()));
                            }
                        }
                    }
                }
            }
            
            ii.improveEquipStats(eqp, stats);
            
            for(Short s : randStat) {
                ii.scrollOptionEquipWithChaos(eqp, s, false);
            }
            
            for(Short s : randOption) {
                ii.scrollOptionEquipWithChaos(eqp, s, true);
            }
        }
        
        if(stimulantid != -1) {
            eqp = ii.randomizeUpgradeStats(eqp);
        }
        
        MapleInventoryManipulator.addFromDrop(c, item, false, -1);
        c.announce(MaplePacketCreator.getShowItemGain(itemid, (short) 1, true));
        return true;
    }
}
