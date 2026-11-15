"""Run the Sculptor dev client in headless gamescope, and drive the client with xdotool.

The client runs in a gamescope with no window on the desktop, so a run takes no focus from the desktop. A screenshot
step presses F2, the screenshot key of Minecraft, and moves the new screenshot to run/ingame/shots.

Usage:
    $ uv run --no-project tools/ingame/dev_client.py launch
    $ uv run --no-project tools/ingame/dev_client.py steps click:426:236 wait:2 shot:worlds
    $ uv run --no-project tools/ingame/dev_client.py steps key:b 'chat:/scu' shot:undo
    $ uv run --no-project tools/ingame/dev_client.py stop

Steps:
    key:KEYS          xdotool key names separated by spaces, such as key:Escape or 'key:t Return'
    hold:KEY:SECONDS  press one key for SECONDS, such as hold:w:1.2
    type:TEXT         type text
    click:X:Y         left click at a pixel of the 854 by 480 window, the pixel of a screenshot
    rclick:X:Y        right click
    move:DX:DY        move the mouse by DX and DY pixels
    wait:SECONDS
    chat:TEXT         open chat with T, type TEXT and press Return
    shot:NAME         save a screenshot as run/ingame/shots/NAME.png

`launch` returns at the main menu. The log of the client is run/ingame/client.log.

Exit codes:
    0  the command finished
    1  the client did not start, or a step failed
    2  the arguments were unusable, or no client is running
"""

from __future__ import annotations

import argparse
import os
import shutil
import signal
import subprocess
import sys
import time
from dataclasses import dataclass
from enum import Enum
from pathlib import Path


REPO_ROOT = Path(__file__).resolve().parents[2]
RUN_DIR = REPO_ROOT / 'run/ingame'
PID_PATH = RUN_DIR / 'gamescope.pid'
LOG_PATH = RUN_DIR / 'client.log'
SHOT_DIR = RUN_DIR / 'shots'
MINECRAFT_SHOT_DIR = REPO_ROOT / 'run/client/screenshots'
LAUNCH_TIMEOUT_S = 600.0
STOP_TIMEOUT_S = 15.0
SHOT_TIMEOUT_S = 10.0
STEP_GAP_S = 0.3
EXIT_SUCCESS = 0
EXIT_FAILURE = 1
EXIT_USAGE = 2


def main() -> int:
    parser = argparse.ArgumentParser(description='Run and drive the Sculptor dev client in headless gamescope.')
    commands = parser.add_subparsers(dest='command', required=True)
    commands.add_parser(Command.LAUNCH.value, help='start the client and wait for the main menu')
    steps_parser = commands.add_parser(Command.STEPS.value, help='send input steps to the client')
    steps_parser.add_argument('raw_steps', nargs='+', metavar='STEP')
    commands.add_parser(Command.STOP.value, help='stop the client')
    arguments = parser.parse_args()

    try:
        return runCommand(Command(arguments.command), getattr(arguments, 'raw_steps', []))
    except UsageError as exc:
        print(f'dev_client: {exc}', file=sys.stderr)
        return EXIT_USAGE
    except ClientError as exc:
        print(f'dev_client: {exc}', file=sys.stderr)
        return EXIT_FAILURE


def runCommand(command: Command, raw_steps: list[str]) -> int:
    match command:
        case Command.LAUNCH:
            return launchClient()
        case Command.STEPS:
            return runSteps([parseStep(raw_step) for raw_step in raw_steps])
        case Command.STOP:
            return stopClient()


def launchClient() -> int:
    """Start gamescope with `./gradlew runClient`, and return at the main menu.

    Raises:
        UsageError: A client from an earlier `launch` is still running.
        ClientError: The build failed, the client exited, or the log has no `Sound engine started` line within
            `LAUNCH_TIMEOUT_S`.
    """
    running_pid = readRunningPid()
    if running_pid is not None:
        raise UsageError(f'a client is already running as pid {running_pid}. Run `stop` first')

    RUN_DIR.mkdir(parents=True, exist_ok=True)
    with LOG_PATH.open('w') as log_file:
        gamescope = subprocess.Popen(
            [
                'nice',
                '-n',
                '5',
                'gamescope',
                '--backend',
                'headless',
                '-W',
                '1280',
                '-H',
                '720',
                '--',
                './gradlew',
                'runClient',
                '--no-daemon',
                '--max-workers',
                '2',
            ],
            cwd=REPO_ROOT,
            stdout=log_file,
            stderr=subprocess.STDOUT,
            start_new_session=True,
        )
    PID_PATH.write_text(f'{gamescope.pid}\n')

    # the sound engine starts while the loading screen is up. The main menu takes a few seconds more
    MENU_SETTLE_S = 8.0
    deadline = time.monotonic() + LAUNCH_TIMEOUT_S
    while time.monotonic() < deadline:
        log_text = LOG_PATH.read_text(errors='replace')
        if 'Sound engine started' in log_text:
            time.sleep(MENU_SETTLE_S)
            print(f'client up, gamescope pid {gamescope.pid}, log {LOG_PATH}')
            return EXIT_SUCCESS
        if gamescope.poll() is not None or 'BUILD FAILED' in log_text:
            raise ClientError(f'the client stopped before the main menu. See {LOG_PATH}')
        time.sleep(2.0)
    raise ClientError(f'no main menu after {LAUNCH_TIMEOUT_S:.0f} s. See {LOG_PATH}')


def readRunningPid() -> int | None:
    if not PID_PATH.exists():
        return None
    pid = int(PID_PATH.read_text())
    return pid if Path(f'/proc/{pid}').exists() else None


def parseStep(raw_step: str) -> Step:
    """Parse one STEP argument, in a form listed in the module docstring.

    Raises:
        UsageError: The step has an unknown kind, the wrong number of fields, or a field that is not a number.
    """
    raw_kind, _, rest = raw_step.partition(':')
    if raw_kind not in STEP_KINDS:
        raise UsageError(f'unknown step {raw_step!r} (expected a kind such as key:Escape or shot:menu)')

    kind = StepKind(raw_kind)
    try:
        match kind:
            case StepKind.KEY:
                return KeyStep(tuple(rest.split()))
            case StepKind.HOLD:
                key, raw_seconds = rest.rsplit(':', 1)
                return HoldStep(key, float(raw_seconds))
            case StepKind.TYPE:
                return TypeStep(rest)
            case StepKind.CLICK | StepKind.RCLICK:
                raw_x, raw_y = rest.split(':')
                button = MouseButton.LEFT if kind is StepKind.CLICK else MouseButton.RIGHT
                return ClickStep(button, int(raw_x), int(raw_y))
            case StepKind.MOVE:
                raw_dx, raw_dy = rest.split(':')
                return MoveStep(int(raw_dx), int(raw_dy))
            case StepKind.WAIT:
                return WaitStep(float(rest))
            case StepKind.CHAT:
                return ChatStep(rest)
            case StepKind.SHOT:
                return ShotStep(rest)
    except ValueError as exc:
        raise UsageError(f'bad step {raw_step!r} (expected the form in the usage, such as click:426:236)') from exc


def runSteps(steps: list[Step]) -> int:
    pid = readRunningPid()
    if pid is None:
        raise UsageError('no client is running. Run `launch` first')

    environment = readClientEnvironment(pid)
    for step in steps:
        output_line = runStep(environment, step)
        if output_line:
            print(output_line)
        time.sleep(STEP_GAP_S)
    return EXIT_SUCCESS


def readClientEnvironment(gamescope_pid: int) -> dict[str, str]:
    """Return the environment for xdotool, with the X display of gamescope that the client draws to.

    Raises:
        UsageError: No Minecraft JVM runs under the gamescope, such as during the Gradle build.
    """
    for pid in listDescendants(gamescope_pid):
        try:
            command_line = Path(f'/proc/{pid}/cmdline').read_bytes()
            raw_environment = Path(f'/proc/{pid}/environ').read_bytes()
        except OSError:
            continue  # the process exited during the scan
        if b'GradleStart' not in command_line:
            continue

        environment = dict(os.environ)
        for item in raw_environment.split(b'\0'):
            name, _, value = item.partition(b'=')
            if name in (b'DISPLAY', b'XAUTHORITY'):
                environment[name.decode()] = value.decode()
        return environment
    raise UsageError(f'no Minecraft JVM runs under gamescope pid {gamescope_pid} yet')


def listDescendants(root_pid: int) -> list[int]:
    children: dict[int, list[int]] = {}
    for stat_path in Path('/proc').glob('[0-9]*/stat'):
        try:
            stat_text = stat_path.read_text()
        except OSError:
            continue  # the process exited during the scan
        # the command name in parentheses can have spaces. After the last ')' come the state, then the parent pid
        parent_pid = int(stat_text.rpartition(')')[2].split()[1])
        children.setdefault(parent_pid, []).append(int(stat_path.parent.name))

    descendants: list[int] = []
    frontier = [root_pid]
    while frontier:
        for child_pid in children.get(frontier.pop(), []):
            descendants.append(child_pid)
            frontier.append(child_pid)
    return descendants


def runStep(environment: dict[str, str], step: Step) -> str:
    """Send one step to the client, and return a line to print, or an empty string.

    Raises:
        ClientError: xdotool failed, or no screenshot appeared within `SHOT_TIMEOUT_S`.
    """
    match step:
        case KeyStep(keys=keys):
            runXdotool(environment, 'key', '--delay', '80', *keys)
            return ''
        case HoldStep(key=key, seconds=seconds):
            runXdotool(environment, 'keydown', key)
            time.sleep(seconds)
            runXdotool(environment, 'keyup', key)
            return ''
        case TypeStep(text=text):
            runXdotool(environment, 'type', '--delay', '40', text)
            return ''
        case ClickStep(button=button, x=x, y=y):
            runXdotool(environment, 'mousemove', str(x), str(y))
            time.sleep(0.2)
            # a Minecraft GUI list, such as the world list, ignores a click with no time between press and release
            runXdotool(environment, 'mousedown', button.value)
            time.sleep(0.15)
            runXdotool(environment, 'mouseup', button.value)
            return ''
        case MoveStep(dx=dx, dy=dy):
            runXdotool(environment, 'mousemove_relative', '--', str(dx), str(dy))
            return ''
        case WaitStep(seconds=seconds):
            time.sleep(seconds)
            return ''
        case ChatStep(text=text):
            runXdotool(environment, 'key', 't')
            time.sleep(0.6)
            runXdotool(environment, 'type', '--delay', '40', text)
            time.sleep(0.3)
            runXdotool(environment, 'key', 'Return')
            time.sleep(0.8)
            return ''
        case ShotStep(name=name):
            return f'shot {takeScreenshot(environment, name)}'


def runXdotool(environment: dict[str, str], *xdotool_arguments: str) -> None:
    try:
        subprocess.run(['xdotool', *xdotool_arguments], env=environment, check=True)
    except subprocess.CalledProcessError as exc:
        raise ClientError(f'xdotool {" ".join(xdotool_arguments)} failed with exit {exc.returncode}') from exc


def takeScreenshot(environment: dict[str, str], name: str) -> Path:
    """Press F2, and move the new screenshot of Minecraft to `SHOT_DIR` as NAME.png.

    Raises:
        ClientError: No new screenshot appeared within `SHOT_TIMEOUT_S`.
    """
    before = set(MINECRAFT_SHOT_DIR.glob('*.png'))
    runXdotool(environment, 'key', 'F2')

    # ScreenShotHelper writes the PNG with ImageIO.write(), and ImageIO.write() creates the file before the write. Wait
    # before the move
    WRITE_SETTLE_S = 0.5
    deadline = time.monotonic() + SHOT_TIMEOUT_S
    while time.monotonic() < deadline:
        time.sleep(0.2)
        new_shots = set(MINECRAFT_SHOT_DIR.glob('*.png')) - before
        if new_shots:
            time.sleep(WRITE_SETTLE_S)
            SHOT_DIR.mkdir(parents=True, exist_ok=True)
            shot_path = SHOT_DIR / f'{name}.png'
            shutil.move(max(new_shots, key=lambda path: path.stat().st_mtime), shot_path)
            return shot_path
    raise ClientError(f'no screenshot for {name!r} within {SHOT_TIMEOUT_S:.0f} s')


def stopClient() -> int:
    """Stop gamescope and every process under gamescope, the deepest first.

    Raises:
        UsageError: No client from `launch` is running.
        ClientError: A process still runs `STOP_TIMEOUT_S` after the signal.
    """
    gamescope_pid = readRunningPid()
    if gamescope_pid is None:
        raise UsageError('no client is running')

    pids = [gamescope_pid, *listDescendants(gamescope_pid)]
    for pid in reversed(pids):
        try:
            os.kill(pid, signal.SIGTERM)
        except ProcessLookupError:
            continue  # the process exited after the scan

    deadline = time.monotonic() + STOP_TIMEOUT_S
    while time.monotonic() < deadline:
        running = [pid for pid in pids if Path(f'/proc/{pid}').exists()]
        if not running:
            PID_PATH.unlink()
            print(f'stopped {len(pids)} processes')
            return EXIT_SUCCESS
        time.sleep(0.5)
    raise ClientError(f'processes {running} still run {STOP_TIMEOUT_S:.0f} s after SIGTERM')


### vocabulary #########################################################################################################


class Command(Enum):
    LAUNCH = 'launch'
    STEPS = 'steps'
    STOP = 'stop'


class StepKind(Enum):
    KEY = 'key'
    HOLD = 'hold'
    TYPE = 'type'
    CLICK = 'click'
    RCLICK = 'rclick'
    MOVE = 'move'
    WAIT = 'wait'
    CHAT = 'chat'
    SHOT = 'shot'


STEP_KINDS = frozenset(kind.value for kind in StepKind)


class MouseButton(Enum):
    LEFT = '1'
    RIGHT = '3'


@dataclass(frozen=True)
class KeyStep:
    keys: tuple[str, ...]


@dataclass(frozen=True)
class HoldStep:
    key: str
    seconds: float


@dataclass(frozen=True)
class TypeStep:
    text: str


@dataclass(frozen=True)
class ClickStep:
    button: MouseButton
    x: int
    y: int


@dataclass(frozen=True)
class MoveStep:
    dx: int
    dy: int


@dataclass(frozen=True)
class WaitStep:
    seconds: float


@dataclass(frozen=True)
class ChatStep:
    text: str


@dataclass(frozen=True)
class ShotStep:
    name: str


Step = KeyStep | HoldStep | TypeStep | ClickStep | MoveStep | WaitStep | ChatStep | ShotStep


class UsageError(RuntimeError):
    pass


class ClientError(RuntimeError):
    pass


if __name__ == '__main__':
    sys.exit(main())
