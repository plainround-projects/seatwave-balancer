# keepalived and VRRP: the floating address

## What it is

A **load balancer** is itself a machine that can die. If clients know only the address of one balancer, its death takes the whole service down. The usual cure is a second balancer, plus a way to make clients find whichever one is alive. This lab does it with a **floating address** (also called a virtual IP): one IP address, `10.210.10.100`, that belongs to no balancer in particular. At any moment it sits on the network card of exactly one balancer, and clients send their requests to it.

**VRRP**, the Virtual Router Redundancy Protocol (RFC 5798), is the set of rules that decides which machine holds the floating address. The machines that share it tell each other, once a second, "I am here, and my priority is N". If the holder stops talking, another machine takes the address. **keepalived** is the free program that speaks VRRP on Linux, adds and removes the address on the network card, and runs health checks that can make a machine give the address up.

## Why this repository uses it

Module 10 asks two questions about the balancer pair. First: when the balancer that holds the address dies, how long are clients without service, and why? Second: when the dead balancer comes back, does the address move again (a second gap), or does it stay put? keepalived lets the lab answer both with real numbers. The pair lab of the re-measured run (version 1.1.0) shows a gap of 3.07 s for the first question, and `nopreempt` answers the second with "it stays".

## Where it appears

| Path | What it does with keepalived |
|---|---|
| `keepalived/lb1.conf`, `keepalived/lb2.conf` | The settings of each keepalived: its name, the check script, the priority, the addresses of both ends and the floating address. |
| `keepalived/Dockerfile` | Builds the image that holds the program (see `docker.md`). |
| `compose.yaml` | The services `keepalived-1` and `keepalived-2` run the image in the network of `lb1` and `lb2`, with four added capabilities, and mount the settings (see `docker-compose.md`). |
| `haproxy/haproxy.cfg` | The `listen status` section answers `/healthz`, the page the check script asks (see `haproxy-config-walkthrough.md`). |
| `scripts/common.sh` | `address_holder` asks each keepalived container which one has the floating address. |
| `scripts/lab.sh` | The `pair` lab kills the balancer that holds the address and puts the pair back. |
| `src/main/java/.../harness/` | A watch counts answers by balancer and measures the longest gap (see `java-harness-walkthrough-1.md`). |

## How it works underneath

1. **An address on a card.** In an Ethernet network, an IP address is reached through a hardware (MAC) address. Each machine tells the network "this IP is at my MAC" when asked (the ARP protocol). The floating address is added to one card as an extra address (`ip addr add`). Moving it means: remove it from one machine, add it to another, and announce the change with a **gratuitous ARP** (an unasked "this IP is now at my MAC"), so that the other machines forget the old owner at once.
2. **The virtual router.** The machines that share an address form a *virtual router*, with a number, the **VRID** (`virtual_router_id`, here 10). All machines of one virtual router use the same number.
3. **States.** Each machine in the virtual router is in one of these states: **BACKUP** (waiting, does not hold the address), **MASTER** (holds the address, sends the "I am here" messages) or, in keepalived, **FAULT** (not allowed to take part, because a tracked check failed).
4. **Advertisements.** The master sends an *advertisement* every `advert_int` seconds. A backup that hears one knows the master is alive. A normal VRRP sends them to a multicast group (one send, many receivers). The comment in `lb1.conf` says this lab uses **unicast** instead, so the pair needs no multicast on the lab network: each side sends straight to the other's address.
5. **Priority and election.** Each machine has a priority from 1 to 254. The highest wins an election.
6. **The master-down timer.** A backup waits for `3 × advert_int + skew` seconds without hearing the master, then declares it dead and becomes master. The **skew** is `(256 − priority) / 256 × advert_int`: a higher priority waits a little less, so the best machine wins the race. With `advert_int 1`, that is 3 s plus a skew: 0.6055 s for priority 101 and 0.6094 s for priority 100. The two skews differ by only 4 thousandths of a second.
7. **Preempt.** By default a machine with a higher priority takes the address back from a lower-priority master as soon as it returns. That is a second move, a second gap. **nopreempt** switches that off: whoever holds the address keeps it until it fails.
8. **Release.** When the master fails a check it goes to FAULT, removes the address, and sends an advertisement with priority 0, which means "I am leaving". A backup that hears it takes over at once, without waiting for the master-down timer.
9. **Check scripts.** keepalived can run a script every few seconds. If the script fails often enough, keepalived treats the machine as faulty. Here the script asks HAProxy whether it is well.
10. **Namespace sharing.** In this lab each keepalived container shares the network of its balancer (`network_mode: "service:lb1"` in `docker-compose.md`). When the balancer container is killed, its keepalived loses the network card with it.

## Walkthrough of this repository's use

### The settings of `lb1`

The two files are almost the same. This is the whole of `lb1.conf`.

`keepalived/lb1.conf`, lines 1 to 45:

```text
# keepalived for balancer lb1 (Module 10): VRRP between lb1 and lb2 over unicast, so the pair
# needs no multicast on the lab network. Checked against keepalived's keepalived.conf(5) manual
# for 2.3 on 11 November 2026.

global_defs {
  router_id lb1
  # The check below runs as root inside this container, and only a file root owns may be run.
  script_user root
  enable_script_security
}

# This balancer may hold the floating address only while its HAProxy answers.
vrrp_script haproxy_answers {
  script "/usr/bin/wget -q -T 1 -O /dev/null http://127.0.0.1:8404/healthz"
  interval 1
  timeout 2
  fall 2
  rise 2
}

vrrp_instance seatwave_front {
  # Both start as BACKUP with nopreempt: the first to win keeps the address, and a balancer that
  # comes back does not take it away again (one move per failure, not two).
  state BACKUP
  nopreempt
  interface eth0
  virtual_router_id 10
  # Which balancer holds the address first is NOT set by this number. Each waits 3 adverts plus a
  # skew of (256 - priority) / 256 s before it claims the address: 3.6055 s for 101, 3.6094 s for
  # 100, only 4 ms apart, so the keepalived that started first usually wins. The higher priority
  # decides only when both claim at the same moment. The labs read the holder, never assume it.
  priority 101
  # One advertisement a second: the backup takes over after 3 missed ones plus a skew.
  advert_int 1
  unicast_src_ip 10.210.10.21
  unicast_peer {
    10.210.10.22
  }
  virtual_ipaddress {
    10.210.10.100/24 dev eth0
  }
  track_script {
    haproxy_answers
  }
}
```
The language is simple. A **block** has a name, then braces `{ ... }` around its settings. Inside a block, one setting per line: a name and a value.

- The comment at the top says that the file is for balancer `lb1`, that VRRP runs between `lb1` and `lb2` over unicast so the pair needs no multicast on the lab network, and that it was checked against keepalived's manual page `keepalived.conf(5)` for 2.3 on 11 November 2026.

#### `global_defs`

- `global_defs { ... }` holds settings for the whole program.
- `router_id lb1` names this machine in logs and in the messages it sends.
- The comment says why the next two lines exist. `script_user root` runs the check script as root inside this container. `enable_script_security` makes keepalived refuse to run a script as root when any part of the script's path could be changed by an ordinary user, and the comment states it as "only a file root owns may be run". Both are there because the script runs as root.

#### `vrrp_script haproxy_answers`

- `vrrp_script haproxy_answers { ... }` defines a check script and gives it a name, which `track_script` below uses. The comment above says: this balancer may hold the floating address only while its HAProxy answers.
- `script "/usr/bin/wget -q -T 1 -O /dev/null http://127.0.0.1:8404/healthz"` is the command. `wget` is a small download tool (here the busybox one that Alpine ships: `/usr/bin/wget` is a link to `/bin/busybox`, as `ls -l` in the container showed). `-q` keeps it quiet, `-T 1` gives up after 1 second of waiting, `-O /dev/null` throws the downloaded page away, and the address is the balancer's own `/healthz` page, reached over the loopback address `127.0.0.1`, which works because keepalived and HAProxy share one network. A good answer exits with status 0; no answer or an error exits non-zero. I ran the same command in the container: it printed the 200 page and exited 0.
- `interval 1` runs the script every second.
- `timeout 2` kills the script if it has not finished after 2 seconds, and counts that as a failure.
- `fall 2` means 2 failures in a row make the script "failed". `rise 2` means 2 successes in a row make it "good" again.

#### `vrrp_instance seatwave_front`

- `vrrp_instance seatwave_front { ... }` defines one virtual router and names it.
- The comment before `state BACKUP` explains `state BACKUP` and `nopreempt` together. Both balancers start as BACKUP with `nopreempt`: the first to win the election keeps the address, and a balancer that comes back does not take it away again. The comment says "one move per failure, not two". (`nopreempt` only works if the initial state is BACKUP, which is why `state` is not MASTER on either machine.)
- `interface eth0` is the network card that sends the messages and gets the address. `eth0` is the name of the card in the lab network (from the point of view of the balancer's container).
- `virtual_router_id 10` is the VRID, the same in both files.
- The four comment lines above `priority 101` say what the number does and what it does not. "Which balancer holds the address first is NOT set by this number." At the start, each keepalived waits 3 advertisements plus a skew of `(256 - priority) / 256` seconds before it claims the address: 3 + 155/256 = 3.6055 s for priority 101, and 3 + 156/256 = 3.6094 s for 100. The two waits are only 4 ms apart, so the keepalived whose container started first usually claims the address first, whatever its priority. The higher priority decides only when both claim at the same moment. That is why the labs read which side holds the address (`address_holder` in `scripts/common.sh`) and never assume it. Version 1.0.0's comment said "lb1 wins the first election", which the runs did not bear out; see "What to know" below.
- `advert_int 1` is one advertisement a second. The comment says: the backup takes over after 3 missed ones plus a skew.
- `unicast_src_ip 10.210.10.21` is the address this side sends from: `lb1`'s own address.
- `unicast_peer { 10.210.10.22 }` lists the other side. Messages go straight to `lb2`. In `lb2.conf` the two are swapped.
- `virtual_ipaddress { 10.210.10.100/24 dev eth0 }` is the floating address: add `10.210.10.100` with a /24 network mask to the card `eth0` when master, remove it otherwise. I saw it as a "secondary" address on `eth0` of the holder only.
- `track_script { haproxy_answers }` ties the instance to the script above. With no weight given, a failing script puts the instance into the FAULT state, which releases the address.

### What differs in `lb2`

`diff keepalived/lb1.conf keepalived/lb2.conf` prints exactly four differences besides the comment on the first line: the name, the priority, and the two unicast addresses. The comment above the priority is the same in both files. These are the changed lines in `lb2.conf`:

`keepalived/lb2.conf`, line 6:

```text
  router_id lb2
```
`keepalived/lb2.conf`, line 32:

```text
  priority 100
```
`keepalived/lb2.conf`, lines 35 to 37:

```text
  unicast_src_ip 10.210.10.22
  unicast_peer {
    10.210.10.21
```
- `router_id lb2` is the name.
- `priority 100` is one lower than `lb1`'s 101.
- `unicast_src_ip 10.210.10.22` and the peer `10.210.10.21` are the swapped addresses.

Everything else, including `virtual_router_id 10`, the check script and the floating address, is identical. That is how both sides agree they belong to one virtual router.

### What happens at start, in the logs

This is the start of `keepalived-1` and `keepalived-2` on 11 November 2026, in my own run of version 1.0.0 (the old run; `docker compose logs --no-log-prefix keepalived-1`), shortened. Version 1.1.0 changed only a comment in these files, so a start looks the same:

```
Wed Nov 11 07:24:41 2026: Starting Keepalived v2.3.4 (06/10,2025)
Wed Nov 11 07:24:41 2026: Starting VRRP child process, pid=6
Wed Nov 11 07:24:41 2026: Script `haproxy_answers` now returning 1
Wed Nov 11 07:24:41 2026: VRRP_Script(haproxy_answers) failed (exited with status 1)
Wed Nov 11 07:24:42 2026: Script `haproxy_answers` now returning 0
Wed Nov 11 07:24:43 2026: VRRP_Script(haproxy_answers) succeeded
Wed Nov 11 07:24:43 2026: (seatwave_front) Entering BACKUP STATE
Wed Nov 11 07:24:47 2026: (seatwave_front) Entering MASTER STATE
```

(The last line was in `keepalived-2` only; `keepalived-1` stayed a backup.) The sequence is: the script is run once before HAProxy answers (failed), then twice after (succeeded, so `rise 2` is met), the instance becomes BACKUP, and 4 seconds later one of the two decides the master is silent and becomes MASTER. 4 s is the 3 s of the timer plus the skew, rounded to the log's whole seconds.

### What happens when the master's balancer is killed

The pair lab does this under load (`scripts/lab.sh pair`, see `shell-scripts-walkthrough.md`). I did it without load, in my run of version 1.0.0: I killed `lb2` (`docker compose kill -s KILL lb2`) at 07:28:45 while `keepalived-2` was master. The logs:

```
keepalived-2: Wed Nov 11 07:28:45 2026: (seatwave_front) Entering FAULT STATE
keepalived-1: Wed Nov 11 07:28:48 2026: (seatwave_front) Entering MASTER STATE
```

`keepalived-1` took over 3 seconds after the kill. The re-measured pair lab of version 1.1.0 ran the other way round: `keepalived-1` held the address, so the lab killed `lb1`, and its log shows `keepalived-1` in FAULT at 08:42:48 and `keepalived-2` entering MASTER at 08:42:51, again 3 seconds later. Under load that run shows a gap in client answers of 3.07 s (from +11.594 s to +14.668 s). Both fit the arithmetic: the backup's timer is 3.609 s from the last advertisement it heard (priority 100: 3 + 156/256), and that advertisement arrived up to 1 s before the kill, so the takeover comes between 2.609 s and 3.609 s after the kill. Then the address moves and a gratuitous ARP tells the others.

After `scripts/lab.sh pair` starts the killed balancer again and restarts its keepalived, that keepalived enters BACKUP and the other stays MASTER, because of `nopreempt`. In my run of version 1.0.0 the address was still on `keepalived-1` seven seconds after `lb2` came back. In the re-measured run, `keepalived-1` entered BACKUP at 08:43:20 and the lab printed `floating address after lb1 came back: keepalived-2`.

### What happens when HAProxy stops answering but its container lives

The kill above takes the whole network with it. A frozen balancer is another case: its container is alive, and only the check script can notice. In my run of version 1.0.0, I paused `lb1` while `keepalived-1` was master (`docker compose pause lb1`, which freezes its processes). The logs:

```
keepalived-1: Wed Nov 11 07:39:32 2026: VRRP_Script(haproxy_answers) failed (exited with status 1)
keepalived-1: Wed Nov 11 07:39:32 2026: (seatwave_front) Entering FAULT STATE
keepalived-2: Wed Nov 11 07:39:32 2026: (seatwave_front) Entering MASTER STATE
keepalived-1: Wed Nov 11 07:39:38 2026: VRRP_Script(haproxy_answers) succeeded
keepalived-1: Wed Nov 11 07:39:38 2026: (seatwave_front) Entering BACKUP STATE
```

The pause was at 07:39:28; the script failed twice (`fall 2`, each failure a 1 to 2 second wait) and at 07:39:32 `keepalived-1` went to FAULT and the other became MASTER in the same second, which is the release advertisement at work (no master-down timer). After `docker compose unpause lb1`, `keepalived-1` returned to BACKUP at 07:39:38 (`rise 2`) and `keepalived-2` kept the address.

### What to know about this design

- **Priority 101 against 100 decides almost nothing.** Both skews are about 0.61 s, 4 ms apart. In the four fresh starts of version 1.0.0 I know of (the old README's and three of mine), `keepalived-2` held the address three times and `keepalived-1` once, although `lb1` has the higher priority; in the re-measured run of version 1.1.0, `keepalived-1` held it. Which one wins is decided by small differences in start-up timing, not by the priority. Version 1.1.0's comment in both files says exactly that, and the labs read the holder instead of assuming it. If the lab needed `lb1` to win, a much bigger priority difference would be needed, with a longer wait before the lower side declares itself master.
- **Both balancers share one Docker host and one network.** If the host dies, both die. The README says so under "What is simplified".
- **The floating address exists only on the lab network.** The Mac cannot reach `10.210.10.100`; the harness container can.
- **Split brain.** If the two sides cannot hear each other but both are alive, both become master and both hold the address. `scripts/lab.sh` refuses to run the pair lab when the address is "not on exactly one balancer".

## Try it yourself

I ran these on 11 November 2026 (Docker 24.0.6, Compose v2.23.0-desktop.1) in a copy of the repository at version 1.0.0 (the old run) whose Compose project name is `seatwave-balancer-notes`; version 1.1.0 changed only comments in the keepalived files, so the steps behave the same; in the repository itself the name is `seatwave-balancer`, so use `-p seatwave-balancer` there. Run the `bash -c` commands from the repository folder. Start the stack with `scripts/stack.sh up --run --yes`. The times in your logs will differ.

1. Which keepalived holds the floating address (the function from `scripts/common.sh`, run in Bash):

```
bash -c 'source scripts/common.sh; address_holder'
```

Output: `keepalived-2` in my first run, and `keepalived-1` in the re-measured run of version 1.1.0 (`scripts/stack.sh status` printed `floating address on: keepalived-1`). Either is possible on a fresh start; see "What to know about this design".

2. The addresses of the two containers' cards (`ip` is the Linux tool that shows them):

```
docker compose -p seatwave-balancer-notes exec -T keepalived-1 ip -4 -o addr show dev eth0
docker compose -p seatwave-balancer-notes exec -T keepalived-2 ip -4 -o addr show dev eth0
```

Output (the holder's card has the extra line with `secondary`):

```
16: eth0    inet 10.210.10.21/24 brd 10.210.10.255 scope global eth0\       valid_lft forever preferred_lft forever
20: eth0    inet 10.210.10.22/24 brd 10.210.10.255 scope global eth0\       valid_lft forever preferred_lft forever
20: eth0    inet 10.210.10.100/24 scope global secondary eth0\       valid_lft forever preferred_lft forever
```

3. The program and its processes:

```
docker compose -p seatwave-balancer-notes exec -T keepalived-1 keepalived --version 2>&1 | head -1
docker compose -p seatwave-balancer-notes exec -T keepalived-1 ps
```

Output:

```
Keepalived v2.3.4 (06/10,2025)
PID   USER     TIME  COMMAND
    1 root      0:00 keepalived --dont-fork --log-console --use-file /etc/keepalived/keepalived.conf
    6 root      0:00 keepalived --dont-fork --log-console --use-file /etc/keepalived/keepalived.conf
   55 root      0:00 ps
```

Process 6 is the VRRP child ("Starting VRRP child process, pid=6" in the log).

4. Run the check script's command by hand inside `keepalived-1`:

```
docker compose -p seatwave-balancer-notes exec -T keepalived-1 sh -c 'wget -q -T 1 -O - http://127.0.0.1:8404/healthz; echo rc=$?'
```

Output:

```
<html><body><h1>200 OK</h1>
Service ready.
</body></html>
rc=0
```

5. Move the address by killing the balancer that holds it, watch, and put the pair back (this is what `scripts/lab.sh pair` does, without load). The address holder here is `keepalived-2`, so the balancer is `lb2`; swap the numbers if `address_holder` says `keepalived-1`:

```
bash -c 'source scripts/common.sh; echo "before: $(address_holder)"'
docker compose -p seatwave-balancer-notes kill -s KILL lb2
sleep 8
bash -c 'source scripts/common.sh; echo "after kill: $(address_holder)"'
docker compose -p seatwave-balancer-notes start lb2
docker compose -p seatwave-balancer-notes restart keepalived-2
sleep 7
bash -c 'source scripts/common.sh; echo "after return: $(address_holder)"'
```

Output:

```
before: keepalived-2
 Container seatwave-balancer-notes-lb2-1  Killed
after kill: keepalived-1
 Container seatwave-balancer-notes-lb2-1  Started
 Container seatwave-balancer-notes-keepalived-2-1  Started
after return: keepalived-1
```

The address moved to `keepalived-1` after the kill and stayed there after `lb2` returned (`nopreempt`).

## Mistakes people make

- **Two machines, one VRID mismatch.** If the two files had different `virtual_router_id` values, each side would think it was alone and both would hold the address. The files here have the same number, and `scripts/lab.sh pair` refuses to run unless exactly one side holds the address.
- **Multicast in a container network.** The default VRRP uses multicast. Here `unicast_src_ip` and `unicast_peer` avoid the problem.
- **`nopreempt` with `state MASTER`.** keepalived ignores `nopreempt` unless the initial state is `BACKUP`. Both files start as `BACKUP`.
- **A check that looks at the wrong thing.** A script that asked "is the HAProxy process alive?" would miss a frozen one. The script asks for `/healthz`, which only an answering HAProxy serves.
- **Forgetting the capabilities.** Without `NET_ADMIN` keepalived cannot add the address; without `NET_RAW` it cannot send VRRP. The Compose file grants exactly those two, and the two that run the script (`SETUID`, `SETGID`).
- **Forgetting that a restarted balancer needs a restarted keepalived.** The keepalived container is attached to the old network of the balancer container. `scripts/lab.sh` restarts it after it starts the balancer.
- **Thinking a bigger priority wins a race.** See the note on the 4 ms difference above.

## Check yourself

1. What is the floating address for?
   *Clients use one address that always points to a working balancer. VRRP moves it from a failed balancer to the other.*
2. Why is the takeover after a kill 3 seconds and not instant?
   *The backup declares the master dead only after 3 missed advertisements plus a skew (about 3.6 s from the last advertisement). A planned release (a failed check) is faster because the master announces it is leaving.*
3. What does `nopreempt` change?
   *A balancer that returns does not take the address back, so one failure causes one move, not two.*
4. What does `track_script { haproxy_answers }` do?
   *If the script fails twice in a row (`fall 2`), the instance goes to FAULT and gives up the address; after two good runs (`rise 2`) it can take part again.*
5. Why does each keepalived share its balancer's network?
   *So the address lands on the balancer's own card, and when the balancer's container dies, its keepalived loses the network with it, as a dead machine would.*

## Further reading

- VRRP, version 3, RFC 5798 (states, advertisements, priority, master-down interval, skew time): https://www.rfc-editor.org/rfc/rfc5798
- keepalived 2.3 manual page `keepalived.conf(5)` (`global_defs`, `vrrp_script`, `vrrp_instance`, `nopreempt`, `unicast_peer`, `track_script`): https://www.keepalived.org/manpage.html
- Address Resolution Protocol, RFC 826, and gratuitous ARP in RFC 5227: https://www.rfc-editor.org/rfc/rfc826 and https://www.rfc-editor.org/rfc/rfc5227
- Alpine Linux packages (keepalived): https://pkgs.alpinelinux.org/packages?name=keepalived
