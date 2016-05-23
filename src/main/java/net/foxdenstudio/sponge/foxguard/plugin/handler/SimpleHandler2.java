/*
 * This file is part of FoxGuard, licensed under the MIT License (MIT).
 *
 * Copyright (c) gravityfox - https://gravityfox.net/
 * Copyright (c) contributors
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */

package net.foxdenstudio.sponge.foxguard.plugin.handler;

import com.google.common.collect.ImmutableList;
import net.foxdenstudio.sponge.foxcore.common.FCUtil;
import net.foxdenstudio.sponge.foxcore.plugin.command.util.AdvCmdParser;
import net.foxdenstudio.sponge.foxcore.plugin.command.util.ProcessResult;
import net.foxdenstudio.sponge.foxcore.plugin.util.CacheMap;
import net.foxdenstudio.sponge.foxguard.plugin.Flag;
import net.foxdenstudio.sponge.foxguard.plugin.FoxGuardMain;
import net.foxdenstudio.sponge.foxguard.plugin.IFlag;
import net.foxdenstudio.sponge.foxguard.plugin.IFlag2;
import net.foxdenstudio.sponge.foxguard.plugin.listener.util.EventResult;
import net.foxdenstudio.sponge.foxguard.plugin.object.factory.IHandlerFactory;
import net.foxdenstudio.sponge.foxguard.plugin.util.ExtraContext;
import ninja.leaping.configurate.ConfigurationOptions;
import ninja.leaping.configurate.commented.CommentedConfigurationNode;
import ninja.leaping.configurate.hocon.HoconConfigurationLoader;
import ninja.leaping.configurate.loader.ConfigurationLoader;
import org.mapdb.Atomic;
import org.mapdb.DB;
import org.mapdb.DBMaker;
import org.mapdb.Serializer;
import org.spongepowered.api.Sponge;
import org.spongepowered.api.command.CommandException;
import org.spongepowered.api.command.CommandSource;
import org.spongepowered.api.command.source.ProxySource;
import org.spongepowered.api.entity.living.player.Player;
import org.spongepowered.api.entity.living.player.User;
import org.spongepowered.api.event.Event;
import org.spongepowered.api.text.Text;
import org.spongepowered.api.text.action.TextActions;
import org.spongepowered.api.text.format.TextColor;
import org.spongepowered.api.text.format.TextColors;
import org.spongepowered.api.util.GuavaCollectors;
import org.spongepowered.api.util.StartsWithPredicate;
import org.spongepowered.api.util.Tristate;

import javax.annotation.Nullable;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

import static net.foxdenstudio.sponge.foxcore.plugin.util.Aliases.*;

public class SimpleHandler2 extends HandlerBase implements IHandler2 {

    private final List<Group> groups;
    private final Map<String, List<Entry>> groupPermissions;

    private final List<Entry> defaultPermissions;
    private final Map<String, Map<Set<IFlag2>, Tristate>> groupPermCache;
    private final Map<User, Map<Set<IFlag2>, Tristate>> userPermCache;

    private final Map<Set<IFlag2>, Tristate> defaultPermCache;
    private PassiveOptions passiveOption = PassiveOptions.PASSTHROUGH;
    private Group passiveGroup;
    private Map<Set<IFlag2>, Tristate> passiveGroupCacheRef;
    private final Map<Set<IFlag2>, Tristate> passivePermCache;

    public SimpleHandler2(String name, int priority) {
        this(name, priority,
                new HashMap<>(),
                new ArrayList<>());
    }

    public SimpleHandler2(String name, int priority,
                          Map<String, List<Entry>> groupPermissions,
                          List<Entry> defaultPermissions) {
        super(name, priority);
        this.groups = new ArrayList<>();

        this.groupPermissions = groupPermissions;
        this.defaultPermissions = defaultPermissions;

        this.groupPermCache = new CacheMap<>((k1, m1) -> {
            if (k1 instanceof String) {
                List<Entry> entries = SimpleHandler2.this.groupPermissions.get(k1);
                Map<Set<IFlag2>, Tristate> map = new CacheMap<>((k2, m2) -> {
                    if (k2 instanceof Set) {
                        for (Object o : (Set) k2) {
                            if (!(o instanceof IFlag2)) return null;
                        }
                        Set<IFlag2> flagSet = (Set<IFlag2>) k2;
                        Tristate state = Tristate.UNDEFINED;
                        for (Entry entry : entries) {
                            if (flagSet.containsAll(entry.set)) {
                                state = entry.state;
                                break;
                            }
                        }
                        m2.put(flagSet, state);
                        return state;
                    } else return null;
                });
                m1.put((String) k1, map);
                return map;
            } else return null;
        });
        this.defaultPermCache = new CacheMap<>((k, m) -> {
            if (k instanceof Set) {
                for (Object o : (Set) k) {
                    if (!(o instanceof IFlag2)) return null;
                }
                Set<IFlag2> flagSet = (Set<IFlag2>) k;
                Tristate state = Tristate.UNDEFINED;
                for (Entry entry : SimpleHandler2.this.defaultPermissions) {
                    if (flagSet.containsAll(entry.set)) {
                        state = entry.state;
                        break;
                    }
                }
                m.put(flagSet, state);
                return state;
            } else return null;
        });
        this.userPermCache = new CacheMap<>((k, m) -> {
            if (k instanceof User) {
                for (Group g : groups) {
                    if (FCUtil.isUserInCollection(g.set, (User) k)) {
                        Map<Set<IFlag2>, Tristate> map = groupPermCache.get(g.name);
                        m.put(((User) k), map);
                        return map;
                    }
                }
                m.put((User) k, defaultPermCache);
                return defaultPermCache;
            } else return null;
        });
        this.passivePermCache = new CacheMap<>((k, m) -> {
            if (k instanceof Set) {
                for (Object o : (Set) k) {
                    if (!(o instanceof IFlag2)) return null;
                }
                Set<IFlag2> flagSet = (Set<IFlag2>) k;
                Tristate state = Tristate.UNDEFINED;
                switch (passiveOption) {
                    case ALLOW:
                        state = Tristate.TRUE;
                        break;
                    case DENY:
                        state = Tristate.FALSE;
                        break;
                    case GROUP:
                        state = passiveGroupCacheRef.get(flagSet);
                        break;
                    case DEFAULT:
                        state = defaultPermCache.get(flagSet);
                        break;
                }
                m.put(flagSet, state);
                return state;
            } else return null;
        });
    }

    @Override
    public ProcessResult modify(CommandSource source, String arguments) throws CommandException {
        if (!source.hasPermission("foxguard.command.modify.objects.modify.handlers")) {
            if (source instanceof ProxySource) source = ((ProxySource) source).getOriginalSource();
            if (source instanceof Player && !this.owners.contains(source)) return ProcessResult.failure();
        }
        AdvCmdParser.ParseResult parse = AdvCmdParser.builder().arguments(arguments).parse();
        if (parse.args.length > 0) {
            if (isIn(GROUPS_ALIASES, parse.args[0])) {
                if (parse.args.length > 1) {
                    Set<User> set;
                    if (isIn(OWNER_GROUP_ALIASES, parse.args[1])) {
                        set = this.owners;
                    } else if (isIn(MEMBER_GROUP_ALIASES, parse.args[1])) {
                        set = this.members;
                    } else {
                        return ProcessResult.of(false, Text.of(TextColors.RED, "Not a valid group!"));
                    }
                    if (parse.args.length > 2) {
                        UserOperations op;
                        if (parse.args[2].equalsIgnoreCase("add")) {
                            op = UserOperations.ADD;
                        } else if (parse.args[2].equalsIgnoreCase("remove")) {
                            op = UserOperations.REMOVE;
                        } else if (parse.args[2].equalsIgnoreCase("set")) {
                            op = UserOperations.SET;
                        } else {
                            return ProcessResult.of(false, Text.of("Not a valid operation!"));
                        }
                        if (parse.args.length > 3) {
                            int successes = 0;
                            int failures = 0;
                            List<String> names = new ArrayList<>();
                            Collections.addAll(names, Arrays.copyOfRange(parse.args, 3, parse.args.length));
                            List<User> argUsers = new ArrayList<>();
                            for (String name : names) {
                                Optional<User> optUser = FoxGuardMain.instance().getUserStorage().get(name);
                                if (optUser.isPresent() && !FCUtil.isUserInCollection(argUsers, optUser.get()))
                                    argUsers.add(optUser.get());
                                else failures++;
                            }
                            switch (op) {
                                case ADD:
                                    for (User user : argUsers) {
                                        if (!FCUtil.isUserInCollection(set, user) && set.add(user))
                                            successes++;
                                        else failures++;
                                    }
                                    break;
                                case REMOVE:
                                    for (User user : argUsers) {
                                        if (FCUtil.isUserInCollection(set, user)) {
                                            set.removeIf(u -> u.getUniqueId().equals(user.getUniqueId()));
                                            successes++;
                                        } else failures++;
                                    }
                                    break;
                                case SET:
                                    set.clear();
                                    for (User user : argUsers) {
                                        set.add(user);
                                        successes++;
                                    }
                            }
                            return ProcessResult.of(true, Text.of("Modified list with " + successes + " successes and " + failures + " failures."));
                        } else {
                            return ProcessResult.of(false, Text.of("Must specify one or more users!"));
                        }
                    } else {
                        return ProcessResult.of(false, Text.of("Must specify an operation!"));
                    }
                } else {
                    return ProcessResult.of(false, Text.of("Must specify a group!"));
                }
            } else if (isIn(SET_ALIASES, parse.args[0])) {
                Map<IFlag, Tristate> map;
                if (parse.args.length > 1) {
                    if (isIn(OWNER_GROUP_ALIASES, parse.args[1])) {
                        map = ownerPermissions;
                    } else if (isIn(MEMBER_GROUP_ALIASES, parse.args[1])) {
                        map = memberPermissions;
                    } else if (isIn(DEFAULT_GROUP_ALIASES, parse.args[1])) {
                        map = defaultPermissions;
                    } else {

                        return ProcessResult.of(false, Text.of("Not a valid group!"));
                    }
                } else {
                    return ProcessResult.of(false, Text.of("Must specify a group!"));
                }
                if (parse.args.length > 2) {
                    IFlag flag;
                    if (parse.args[2].equalsIgnoreCase("all")) {
                        flag = null;
                    } else {
                        flag = Flag.flagFrom(parse.args[2]);
                        if (flag == null) {
                            return ProcessResult.of(false, Text.of("Not a valid flag!"));
                        }
                    }
                    if (parse.args.length > 3) {
                        if (isIn(CLEAR_ALIASES, parse.args[3])) {
                            if (flag == null) {
                                map.clear();
                                clearCache();
                                return ProcessResult.of(true, Text.of("Successfully cleared flags!"));
                            } else {
                                map.remove(flag);
                                clearCache();
                                return ProcessResult.of(true, Text.of("Successfully cleared flag!"));
                            }
                        } else {
                            Tristate tristate = tristateFrom(parse.args[3]);
                            if (tristate == null) {
                                return ProcessResult.of(false, Text.of("Not a valid value!"));
                            }
                            if (flag == null) {
                                for (IFlag thatExist : Flag.getFlags()) {
                                    map.put(thatExist, tristate);
                                }
                                clearCache();
                                return ProcessResult.of(true, Text.of("Successfully set flags!"));
                            } else {
                                map.put(flag, tristate);
                                clearCache();
                                return ProcessResult.of(true, Text.of("Successfully set flag!"));
                            }
                        }
                    } else {
                        return ProcessResult.of(false, Text.of("Must specify a value!"));
                    }
                } else {
                    return ProcessResult.of(false, Text.of("Must specify a flag!"));
                }
            } else if (isIn(PASSIVE_ALIASES, parse.args[0])) {
                if (parse.args.length > 1) {
                    if (isIn(TRUE_ALIASES, parse.args[1])) {
                        this.passiveOption = PassiveOptions.ALLOW;
                        return ProcessResult.of(true, Text.of("Successfully set passive option!"));
                    } else if (isIn(FALSE_ALIASES, parse.args[1])) {
                        this.passiveOption = PassiveOptions.DENY;
                        return ProcessResult.of(true, Text.of("Successfully set passive option!"));
                    } else if (isIn(PASSTHROUGH_ALIASES, parse.args[1])) {
                        this.passiveOption = PassiveOptions.PASSTHROUGH;
                        return ProcessResult.of(true, Text.of("Successfully set passive option!"));
                    } else if (isIn(OWNER_GROUP_ALIASES, parse.args[1])) {
                        this.passiveOption = PassiveOptions.OWNER;
                        return ProcessResult.of(true, Text.of("Successfully set passive option!"));
                    } else if (isIn(MEMBER_GROUP_ALIASES, parse.args[1])) {
                        this.passiveOption = PassiveOptions.MEMBER;
                        return ProcessResult.of(true, Text.of("Successfully set passive option!"));
                    } else if (isIn(DEFAULT_GROUP_ALIASES, parse.args[1])) {
                        this.passiveOption = PassiveOptions.DEFAULT;
                        return ProcessResult.of(true, Text.of("Successfully set passive option!"));
                    } else {
                        return ProcessResult.of(false, Text.of("Not a valid option!"));
                    }
                } else {
                    return ProcessResult.of(false, Text.of("Must specify an option!"));
                }
            } else {
                return ProcessResult.of(false, Text.of("Not a valid SimpleHandler command!"));
            }
        } else {
            return ProcessResult.of(false, Text.of("Must specify a command!"));
        }
    }

    @Override
    public List<String> modifySuggestions(CommandSource source, String arguments) throws CommandException {
        AdvCmdParser.ParseResult parse = AdvCmdParser.builder()
                .arguments(arguments)
                .excludeCurrent(true)
                .autoCloseQuotes(true)
                .parse();
        if (parse.current.type.equals(AdvCmdParser.CurrentElement.ElementType.ARGUMENT)) {
            if (parse.current.index == 0) {
                return ImmutableList.of("set", "group", "passive").stream()
                        .filter(new StartsWithPredicate(parse.current.token))
                        .map(args -> parse.current.prefix + args)
                        .collect(GuavaCollectors.toImmutableList());
            } else if (parse.current.index == 1) {
                if (isIn(GROUPS_ALIASES, parse.args[0])) {
                    return ImmutableList.of("owner", "member").stream()
                            .filter(new StartsWithPredicate(parse.current.token))
                            .map(args -> parse.current.prefix + args)
                            .collect(GuavaCollectors.toImmutableList());
                } else if (isIn(SET_ALIASES, parse.args[0])) {
                    return ImmutableList.of("owner", "member").stream()
                            .filter(new StartsWithPredicate(parse.current.token))
                            .map(args -> parse.current.prefix + args)
                            .collect(GuavaCollectors.toImmutableList());
                } else if (isIn(PASSIVE_ALIASES, parse.args[0])) {
                    return ImmutableList.of("true", "false", "passthrough", "owner", "member", "default").stream()
                            .filter(new StartsWithPredicate(parse.current.token))
                            .map(args -> parse.current.prefix + args)
                            .collect(GuavaCollectors.toImmutableList());
                }
            } else if (parse.current.index == 2) {
                if (isIn(GROUPS_ALIASES, parse.args[0])) {
                    return ImmutableList.of("add", "remove", "set").stream()
                            .filter(new StartsWithPredicate(parse.current.token))
                            .map(args -> parse.current.prefix + args)
                            .collect(GuavaCollectors.toImmutableList());
                } else if (isIn(SET_ALIASES, parse.args[0])) {
                    return Flag.getFlags().stream()
                            .map(IFlag::flagName)
                            .filter(new StartsWithPredicate(parse.current.token))
                            .map(args -> parse.current.prefix + args)
                            .collect(GuavaCollectors.toImmutableList());
                }
            } else if (parse.current.index == 3) {
                if (isIn(GROUPS_ALIASES, parse.args[0])) {
                    Set<User> set;
                    if (isIn(OWNER_GROUP_ALIASES, parse.args[1])) {
                        set = this.owners;
                    } else if (isIn(MEMBER_GROUP_ALIASES, parse.args[1])) {
                        set = this.members;
                    } else {
                        return ImmutableList.of();
                    }
                    if (parse.args[2].equalsIgnoreCase("set")) {
                        return Sponge.getGame().getServer().getOnlinePlayers().stream()
                                .map(Player::getName)
                                .filter(new StartsWithPredicate(parse.current.token))
                                .map(args -> parse.current.prefix + args)
                                .collect(GuavaCollectors.toImmutableList());
                    } else if (parse.args[2].equalsIgnoreCase("add")) {
                        return Sponge.getGame().getServer().getOnlinePlayers().stream()
                                .filter(player -> !FCUtil.isUserInCollection(set, player))
                                .map(Player::getName)
                                .filter(new StartsWithPredicate(parse.current.token))
                                .map(args -> parse.current.prefix + args)
                                .collect(GuavaCollectors.toImmutableList());
                    } else if (parse.args[2].equalsIgnoreCase("remove")) {
                        return Sponge.getGame().getServer().getOnlinePlayers().stream()
                                .filter(player -> FCUtil.isUserInCollection(set, player))
                                .map(Player::getName)
                                .filter(new StartsWithPredicate(parse.current.token))
                                .map(args -> parse.current.prefix + args)
                                .collect(GuavaCollectors.toImmutableList());
                    }
                } else if (isIn(SET_ALIASES, parse.args[0])) {
                    return ImmutableList.of("true", "false", "passthrough", "clear").stream()
                            .filter(new StartsWithPredicate(parse.current.token))
                            .map(args -> parse.current.prefix + args)
                            .collect(GuavaCollectors.toImmutableList());
                }
            } else if (parse.current.index > 3) {
                if (isIn(GROUPS_ALIASES, parse.args[0])) {
                    Set<User> set;
                    if (isIn(OWNER_GROUP_ALIASES, parse.args[1])) {
                        set = this.owners;
                    } else if (isIn(MEMBER_GROUP_ALIASES, parse.args[1])) {
                        set = this.members;
                    } else {
                        return ImmutableList.of();
                    }
                    if (parse.args[2].equalsIgnoreCase("set")) {
                        return Sponge.getGame().getServer().getOnlinePlayers().stream()
                                .map(Player::getName)
                                .filter(new StartsWithPredicate(parse.current.token))
                                .map(args -> parse.current.prefix + args)
                                .collect(GuavaCollectors.toImmutableList());
                    } else if (parse.args[2].equalsIgnoreCase("add")) {
                        return Sponge.getGame().getServer().getOnlinePlayers().stream()
                                .filter(player -> !FCUtil.isUserInCollection(set, player))
                                .map(Player::getName)
                                .filter(alias -> !isIn(Arrays.copyOfRange(parse.args, 2, parse.args.length), alias))
                                .filter(new StartsWithPredicate(parse.current.token))
                                .map(args -> parse.current.prefix + args)
                                .collect(GuavaCollectors.toImmutableList());
                    } else if (parse.args[2].equalsIgnoreCase("remove")) {
                        return Sponge.getGame().getServer().getOnlinePlayers().stream()
                                .filter(player -> FCUtil.isUserInCollection(set, player))
                                .map(Player::getName)
                                .filter(alias -> !isIn(Arrays.copyOfRange(parse.args, 2, parse.args.length), alias))
                                .filter(new StartsWithPredicate(parse.current.token))
                                .map(args -> parse.current.prefix + args)
                                .collect(GuavaCollectors.toImmutableList());
                    }
                }
            }
        } else if (parse.current.type.equals(AdvCmdParser.CurrentElement.ElementType.COMPLETE))
            return ImmutableList.of(parse.current.prefix + " ");
        return ImmutableList.of();
    }

    @Override
    public EventResult handle(@Nullable User user, Set<IFlag2> flags, ExtraContext extra) {
        if (user == null) return EventResult.of(this.passivePermCache.get(flags));
        else return EventResult.of(this.userPermCache.get(user).get(flags));
    }

    @Override
    public EventResult handle(@Nullable User user, IFlag flag, Optional<Event> event, Object... extra) {
        return EventResult.pass();
    }

    @Override
    public String getShortTypeName() {
        return "Simple";
    }

    @Override
    public String getLongTypeName() {
        return "Simple";
    }

    @Override
    public String getUniqueTypeString() {
        return "simple";
    }

    @Override
    public Text details(CommandSource source, String arguments) {
        Text.Builder builder = Text.builder();
        builder.append(Text.of(TextColors.GOLD,
                TextActions.suggestCommand("/foxguard md h " + this.getName() + " group owners add "),
                TextActions.showText(Text.of("Click to Add a Player(s) to Owners")),
                "Owners: "));
        for (User u : owners) {
            builder.append(Text.of(TextColors.RESET,
                    TextActions.suggestCommand("/foxguard md h " + this.getName() + " group owners remove " + u.getName()),
                    TextActions.showText(Text.of("Click to Remove Player \"" + u.getName() + "\" from Owners")),
                    u.getName())).append(Text.of("  "));
        }
        builder.append(Text.of("\n"));
        builder.append(Text.of(TextColors.GREEN,
                TextActions.suggestCommand("/foxguard md h " + this.name + " group members add "),
                TextActions.showText(Text.of("Click to Add a Player(s) to Members")),
                "Members: "));
        for (User u : this.members) {
            builder.append(Text.of(TextColors.RESET,
                    TextActions.suggestCommand("/foxguard md h " + this.name + " group members remove " + u.getName()),
                    TextActions.showText(Text.of("Click to Remove Player \"" + u.getName() + "\" from Members")),
                    u.getName())).append(Text.of("  "));
        }
        builder.append(Text.of("\n"));
        builder.append(Text.of(TextColors.GOLD,
                TextActions.suggestCommand("/foxguard md h " + this.name + " set owners "),
                TextActions.showText(Text.of("Click to Set a Flag")),
                "Owner permissions:\n"));
        for (IFlag f : this.ownerPermissions.keySet().stream().sorted().collect(GuavaCollectors.toImmutableList())) {
            builder.append(
                    Text.builder().append(Text.of("  " + f.toString() + ": "))
                            .append(FCUtil.readableTristateText(ownerPermissions.get(f)))
                            .append(Text.of("\n"))
                            .onClick(TextActions.suggestCommand("/foxguard md h " + this.name + " set owners " + f.flagName() + " "))
                            .onHover(TextActions.showText(Text.of("Click to Change This Flag")))
                            .build()
            );
        }
        builder.append(Text.of(TextColors.GREEN,
                TextActions.suggestCommand("/foxguard md h " + this.name + " set members "),
                TextActions.showText(Text.of("Click to Set a Flag")),
                "Member permissions:\n"));
        for (IFlag f : this.memberPermissions.keySet().stream().sorted().collect(GuavaCollectors.toImmutableList())) {
            builder.append(
                    Text.builder().append(Text.of("  " + f.toString() + ": "))
                            .append(FCUtil.readableTristateText(memberPermissions.get(f)))
                            .append(Text.of("\n"))
                            .onClick(TextActions.suggestCommand("/foxguard md h " + this.name + " set members " + f.flagName() + " "))
                            .onHover(TextActions.showText(Text.of("Click to Change This Flag")))
                            .build()
            );
        }
        builder.append(Text.of(TextColors.RED,
                TextActions.suggestCommand("/foxguard md h " + this.name + " set default "),
                TextActions.showText(Text.of("Click to Set a Flag")),
                "Default permissions:\n"));
        for (IFlag f : this.defaultPermissions.keySet().stream().sorted().collect(GuavaCollectors.toImmutableList())) {
            builder.append(
                    Text.builder().append(Text.of("  " + f.toString() + ": "))
                            .append(FCUtil.readableTristateText(defaultPermissions.get(f)))
                            .append(Text.of("\n"))
                            .onClick(TextActions.suggestCommand("/foxguard md h " + this.name + " set default " + f.flagName() + " "))
                            .onHover(TextActions.showText(Text.of("Click to Change This Flag")))
                            .build()
            );
        }
        builder.append(Text.builder()
                .append(Text.of(TextColors.AQUA, "Passive setting: "))
                .append(Text.of(TextColors.RESET, this.passiveOption.toString()))
                .onClick(TextActions.suggestCommand("/foxguard md h " + this.name + " passive "))
                .onHover(TextActions.showText(Text.of("Click to Change Passive Setting"))).build()
        );
        return builder.build();
    }

    @Override
    public List<String> detailsSuggestions(CommandSource source, String arguments) {
        return ImmutableList.of();
    }

    @Override
    public void save(Path directory) {
        try (DB flagMapDB = DBMaker.fileDB(directory.resolve("flags.db").normalize().toString()).make()) {
            Map<String, String> ownerStorageFlagMap = flagMapDB.hashMap("owners", Serializer.STRING, Serializer.STRING).createOrOpen();
            ownerStorageFlagMap.clear();
            for (Map.Entry<IFlag, Tristate> entry : ownerPermissions.entrySet()) {
                ownerStorageFlagMap.put(entry.getKey().flagName(), entry.getValue().name());
            }
            Map<String, String> memberStorageFlagMap = flagMapDB.hashMap("members", Serializer.STRING, Serializer.STRING).createOrOpen();
            memberStorageFlagMap.clear();
            for (Map.Entry<IFlag, Tristate> entry : memberPermissions.entrySet()) {
                memberStorageFlagMap.put(entry.getKey().flagName(), entry.getValue().name());
            }
            Map<String, String> defaultStorageFlagMap = flagMapDB.hashMap("default", Serializer.STRING, Serializer.STRING).createOrOpen();
            defaultStorageFlagMap.clear();
            for (Map.Entry<IFlag, Tristate> entry : defaultPermissions.entrySet()) {
                defaultStorageFlagMap.put(entry.getKey().flagName(), entry.getValue().name());
            }
            Atomic.String passiveOptionString = flagMapDB.atomicString("passive").createOrOpen();
            passiveOptionString.set(passiveOption.name());
        }
        Path usersFile = directory.resolve("users.cfg");
        CommentedConfigurationNode root;
        ConfigurationLoader<CommentedConfigurationNode> loader =
                HoconConfigurationLoader.builder().setPath(usersFile).build();
        if (Files.exists(usersFile)) {
            try {
                root = loader.load();
            } catch (IOException e) {
                root = loader.createEmptyNode(ConfigurationOptions.defaults());
            }
        } else {
            root = loader.createEmptyNode(ConfigurationOptions.defaults());
        }
        List<CommentedConfigurationNode> ownerNodes = new ArrayList<>();
        for (User user : owners) {
            CommentedConfigurationNode node = loader.createEmptyNode(ConfigurationOptions.defaults());
            node.getNode("username").setValue(user.getName());
            node.getNode("uuid").setValue(user.getUniqueId().toString());
            ownerNodes.add(node);
        }
        root.getNode("owners").setValue(ownerNodes);
        List<CommentedConfigurationNode> memberNodes = new ArrayList<>();
        for (User user : members) {
            CommentedConfigurationNode node = loader.createEmptyNode(ConfigurationOptions.defaults());
            node.getNode("username").setValue(user.getName());
            node.getNode("uuid").setValue(user.getUniqueId().toString());
            memberNodes.add(node);
        }
        root.getNode("members").setValue(memberNodes);
        try {
            loader.save(root);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public PassiveOptions getPassiveOption() {
        return passiveOption;
    }

    public void setPassiveOption(PassiveOptions passiveOption) {
        this.passiveOption = passiveOption;
    }

    private void clearCache() {
        this.groupPermCache.clear();
        this.defaultPermCache.clear();
        this.userPermCache.clear();
    }

    public enum PassiveOptions {
        ALLOW, DENY, PASSTHROUGH, GROUP, DEFAULT;

        public String toString() {
            switch (this) {
                case ALLOW:
                    return "Allow";
                case DENY:
                    return "Deny";
                case PASSTHROUGH:
                    return "Passthrough";
                case GROUP:
                    return "Group";
                case DEFAULT:
                    return "Default";
                default:
                    return "Awut...?";
            }
        }
    }

    private enum UserOperations {
        ADD,
        REMOVE,
        SET
    }

    public class Entry {
        public Set<IFlag2> set;
        public Tristate state;

        public Entry(Set<IFlag2> set, Tristate state) {
            this.set = set;
            this.state = state;
        }
    }

    public class Group {
        public String name;
        public String displayName;
        public TextColor color;
        public Set<User> set;

        public Group(String name, Set<User> set) {
            this(name, set, TextColors.WHITE);
        }

        public Group(String name, Set<User> set, TextColor color) {
            this.name = name.toLowerCase();
            this.displayName = name;
            this.color = color;
            this.set = set;
        }
    }

    public static class Factory implements IHandlerFactory {

        private static final String[] simpleAliases = {"simple", "simp"};

        @Override
        public IHandler create(String name, int priority, String arguments, CommandSource source) {
            SimpleHandler2 handler = new SimpleHandler2(name, priority);
            if (source instanceof Player) handler.addOwner((Player) source);
            return handler;
        }

        @Override
        public IHandler create(Path directory, String name, int priority, boolean isEnabled) {
            Map<IFlag, Tristate> ownerPermissions = new CacheMap<>((k, m) -> Tristate.UNDEFINED);
            Map<IFlag, Tristate> memberPermissions = new CacheMap<>((k, m) -> Tristate.UNDEFINED);
            Map<IFlag, Tristate> defaultPermissions = new CacheMap<>((k, m) -> Tristate.UNDEFINED);
            PassiveOptions option = PassiveOptions.PASSTHROUGH;
            try (DB flagMapDB = DBMaker.fileDB(directory.resolve("flags.db").normalize().toString()).make()) {
                Map<String, String> ownerStorageFlagMap = flagMapDB.hashMap("owners", Serializer.STRING, Serializer.STRING).createOrOpen();
                for (Map.Entry<String, String> entry : ownerStorageFlagMap.entrySet()) {
                    IFlag flag = Flag.flagFrom(entry.getKey());
                    if (flag != null) {
                        Tristate state = Tristate.valueOf(entry.getValue());
                        if (state != null) {
                            ownerPermissions.put(flag, state);
                        }
                    }
                }
                Map<String, String> memberStorageFlagMap = flagMapDB.hashMap("members", Serializer.STRING, Serializer.STRING).createOrOpen();
                for (Map.Entry<String, String> entry : memberStorageFlagMap.entrySet()) {
                    IFlag flag = Flag.flagFrom(entry.getKey());
                    if (flag != null) {
                        Tristate state = Tristate.valueOf(entry.getValue());
                        if (state != null) {
                            memberPermissions.put(flag, state);
                        }
                    }
                }
                Map<String, String> defaultStorageFlagMap = flagMapDB.hashMap("default", Serializer.STRING, Serializer.STRING).createOrOpen();
                for (Map.Entry<String, String> entry : defaultStorageFlagMap.entrySet()) {
                    IFlag flag = Flag.flagFrom(entry.getKey());
                    if (flag != null) {
                        Tristate state = Tristate.valueOf(entry.getValue());
                        if (state != null) {
                            defaultPermissions.put(flag, state);
                        }
                    }
                }
                Atomic.String passiveOptionString = flagMapDB.atomicString("passive").createOrOpen();
                try {
                    PassiveOptions po = PassiveOptions.valueOf(passiveOptionString.get());
                    if (po != null) option = po;
                } catch (IllegalArgumentException ignored) {
                }
            }
            Path usersFile = directory.resolve("users.cfg");
            CommentedConfigurationNode root;
            ConfigurationLoader<CommentedConfigurationNode> loader =
                    HoconConfigurationLoader.builder().setPath(usersFile).build();
            if (Files.exists(usersFile)) {
                try {
                    root = loader.load();
                } catch (IOException e) {
                    root = loader.createEmptyNode(ConfigurationOptions.defaults());
                }
            } else {
                root = loader.createEmptyNode(ConfigurationOptions.defaults());
            }
            List<Optional<User>> owners = root.getNode("owners").getList(o -> {
                if (o instanceof HashMap) {
                    HashMap map = (HashMap) o;
                    String uuidString = (String) map.get("uuid");
                    if (!uuidString.isEmpty()) {
                        return FoxGuardMain.instance().getUserStorage().get(UUID.fromString(uuidString));
                    } else return Optional.empty();
                } else return Optional.empty();
            });
            List<Optional<User>> members = root.getNode("members").getList(o -> {
                if (o instanceof HashMap) {
                    HashMap map = (HashMap) o;
                    String uuidString = (String) map.get("uuid");
                    if (!uuidString.isEmpty()) {
                        return FoxGuardMain.instance().getUserStorage().get(UUID.fromString(uuidString));
                    } else return Optional.empty();
                } else return Optional.empty();
            });
            SimpleHandler2 handler = new SimpleHandler2(name, priority, ownerPermissions, memberPermissions, defaultPermissions);
            owners.stream().filter(Optional::isPresent).forEach(userOptional -> handler.addOwner(userOptional.get()));
            members.stream().filter(Optional::isPresent).forEach(userOptional -> handler.addMember(userOptional.get()));
            handler.setPassiveOption(option);
            return handler;
        }

        @Override
        public String[] getAliases() {
            return simpleAliases;
        }

        @Override
        public String getType() {
            return "simple";
        }

        @Override
        public String getPrimaryAlias() {
            return "simple";
        }

        @Override
        public List<String> createSuggestions(CommandSource source, String arguments, String type) throws CommandException {
            return ImmutableList.of();
        }
    }
}
